/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.types;

import static io.crate.execution.dml.IndexerTest.getIndexer;
import static io.crate.execution.dml.IndexerTest.item;
import static io.crate.testing.Asserts.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.junit.Test;

import io.crate.Streamer;
import io.crate.execution.dml.Indexer;
import io.crate.execution.dml.IndexerTest;
import io.crate.execution.engine.fetch.ReaderContext;
import io.crate.expression.reference.doc.lucene.CollectorContext;
import io.crate.expression.reference.doc.lucene.LuceneCollectorExpression;
import io.crate.expression.reference.doc.lucene.LuceneReferenceResolver;
import io.crate.expression.reference.doc.lucene.StoredRowLookup;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.Reference;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.DataTypeTesting;
import io.crate.testing.IndexEnv;
import io.crate.testing.SQLExecutor;


public abstract class DataTypeTestCase<T> extends CrateDummyClusterServiceUnitTest {

    public abstract DataType<T> getType();

    protected record DataDef<T>(DataType<T> type, String definition, Supplier<T> data) {}

    protected DataDef<T> getDataDef() {
        DataType<T> type = getType();
        return new DataDef<>(type, type.getTypeSignature().toString(), DataTypeTesting.getDataGenerator(type));
    }

    public void test_reference_resolver() throws Exception {
        DataDef<T> dataDef = getDataDef();
        doReferenceResolveTest(dataDef.type, dataDef.definition, dataDef.data.get());
    }

    public void test_reference_resolver_index_off() throws Exception {
        DataDef<T> dataDef = getDataDef();
        doReferenceResolveTest(dataDef.type, dataDef.definition + " INDEX OFF", dataDef.data.get());
    }

    public void test_reference_resolver_docvalues_off() throws Exception {
        DataDef<T> dataDef = getDataDef();
        doReferenceResolveTest(dataDef.type, dataDef.definition + " STORAGE WITH (columnstore=false)", dataDef.data.get());
    }

    public void test_reference_resolver_index_and_docvalues_off() throws Exception {
        DataDef<T> dataDef = getDataDef();
        doReferenceResolveTest(dataDef.type, dataDef.definition + " INDEX OFF STORAGE WITH (columnstore=false)", dataDef.data.get());
    }

    protected void doReferenceResolveTest(DataType<T> type, String definition, T data) throws Exception {

        StorageSupport<? super T> storageSupport = type.storageSupport();
        assumeTrue("Data type " + type + " does not support storage", storageSupport != null);

        var sqlExecutor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (id int, x " + definition + ")");

        DocTableInfo table = sqlExecutor.resolveTableInfo("tbl");
        Reference reference = table.getReference(ColumnIdent.of("x"));
        assertThat(reference).isNotNull();

        try (var indexEnv = new IndexEnv(
                sqlExecutor.nodeCtx,
                THREAD_POOL,
                table,
                clusterService.state(),
                Version.CURRENT)) {

            Indexer indexer = getIndexer(sqlExecutor, table.ident().name(), "x");
            ParsedDocument doc = indexer.index(item(data));
            IndexWriter writer = indexEnv.writer();
            writer.addDocument(doc.doc().getFields());
            writer.commit();

            LuceneReferenceResolver luceneReferenceResolver = indexEnv.luceneReferenceResolver();
            LuceneCollectorExpression<?> docValueImpl = luceneReferenceResolver.getImplementation(reference);
            assertThat(docValueImpl).isNotNull();

            IndexSearcher indexSearcher;
            try {
                indexSearcher = new IndexSearcher(DirectoryReader.open(writer));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            List<LeafReaderContext> leaves = indexSearcher.getTopReaderContext().leaves();
            assertThat(leaves).hasSize(1);
            LeafReaderContext leafReader = leaves.getFirst();

            Weight weight = indexSearcher.createWeight(
                new MatchAllDocsQuery(),
                ScoreMode.COMPLETE_NO_SCORES,
                1.0f
            );

            Scorer scorer = weight.scorer(leafReader);
            CollectorContext collectorContext = new CollectorContext(1, () -> StoredRowLookup.create(table, "index"));
            ReaderContext readerContext = new ReaderContext(leafReader);
            DocIdSetIterator iterator = scorer.iterator();
            int nextDoc = iterator.nextDoc();

            docValueImpl.startCollect(collectorContext);
            docValueImpl.setNextReader(readerContext);
            docValueImpl.setNextDocId(nextDoc);
            assertEquals((T) docValueImpl.value(), data);
        }
    }

    protected void assertEquals(T actual, T expected) {
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void test_translog_streaming_roundtrip() throws Exception {
        DataType<T> type = getType();
        assumeTrue("Data type " + type + " does not support storage", type.storageSupport() != null);
        var sqlExecutor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (id int, x " + type.getTypeSignature().toString() + ")");

        Supplier<T> dataGenerator = DataTypeTesting.getDataGenerator(type);
        DocTableInfo table = sqlExecutor.resolveTableInfo("tbl");
        Reference reference = table.getReference(ColumnIdent.of("x"));
        assertThat(reference).isNotNull();

        T value = dataGenerator.get();
        Indexer indexer = getIndexer(sqlExecutor, table.ident().name(), "x");
        ParsedDocument doc = indexer.index(item(value));
        IndexerTest.assertTranslogParses(doc, table);
    }

    @Test
    public void test_type_streaming_roundtrip() throws Exception {
        DataType<T> type = getType();
        BytesStreamOutput out = new BytesStreamOutput();
        DataTypes.toStream(type, out);

        StreamInput in = out.bytes().streamInput();
        DataType<?> fromStream = DataTypes.fromStream(in);
        assertThat(fromStream.id()).isEqualTo(type.id());
        assertThat(fromStream.characterMaximumLength()).isEqualTo(type.characterMaximumLength());
        assertThat(fromStream.numericPrecision()).isEqualTo(type.numericPrecision());
    }

    @Test
    public void test_value_streaming_roundtrip() throws Exception {
        DataType<T> type = getType();
        Supplier<T> dataGenerator = DataTypeTesting.getDataGenerator(type);
        T value = dataGenerator.get();

        Streamer<T> streamer = type.streamer();
        BytesStreamOutput out = new BytesStreamOutput();
        streamer.writeValueTo(out, value);
        streamer.writeValueTo(out, null);

        StreamInput in = out.bytes().streamInput();
        assertThat(type.compare(streamer.readValueFrom(in), value)).isEqualTo(0);
        assertThat(streamer.readValueFrom(in)).isNull();
    }
}
