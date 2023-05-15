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

package io.crate.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.junit.Test;

import io.crate.common.collections.Maps;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.sql.tree.ColumnPolicy;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.SQLExecutor;
import io.crate.types.ArrayType;
import io.crate.types.DataTypes;

public class ReferenceTest extends CrateDummyClusterServiceUnitTest {

    @Test
    public void testEquals() {
        RelationName relationName = new RelationName("doc", "test");
        ReferenceIdent referenceIdent = new ReferenceIdent(relationName, "object_column");
        var dataType1 = new ArrayType<>(DataTypes.UNTYPED_OBJECT);
        var dataType2 = new ArrayType<>(DataTypes.UNTYPED_OBJECT);
        Symbol defaultExpression1 = Literal.of(dataType1, List.of(Map.of("f", 10)));
        Symbol defaultExpression2 = Literal.of(dataType2, List.of(Map.of("f", 10)));
        SimpleReference reference1 = new SimpleReference(referenceIdent,
                                                         RowGranularity.DOC,
                                                         dataType1,
                                                         1,
                                                         defaultExpression1);
        SimpleReference reference2 = new SimpleReference(referenceIdent,
                                                         RowGranularity.DOC,
                                                         dataType2,
                                                         1,
                                                         defaultExpression2);
        assertThat(reference1).isEqualTo(reference2);
    }

    @Test
    public void testStreaming() throws Exception {
        RelationName relationName = new RelationName("doc", "test");
        ReferenceIdent referenceIdent = new ReferenceIdent(relationName, "object_column");
        var dataType = new ArrayType<>(DataTypes.UNTYPED_OBJECT);
        SimpleReference reference = new SimpleReference(
            referenceIdent,
            RowGranularity.DOC,
            dataType,
            ColumnPolicy.STRICT,
            IndexType.FULLTEXT,
            false,
            true,
            0,
            0,
            Literal.of(dataType, List.of(Map.of("f", 10))
            )
        );

        BytesStreamOutput out = new BytesStreamOutput();
        Reference.toStream(out, reference);

        StreamInput in = out.bytes().streamInput();
        Reference reference2 = Reference.fromStream(in);

        assertThat(reference2).isEqualTo(reference);
    }

    @Test
    public void test_streaming_of_reference_position_before_4_6_0() throws Exception {
        RelationName relationName = new RelationName("doc", "test");
        ReferenceIdent referenceIdent = new ReferenceIdent(relationName, "object_column");
        var dataType = new ArrayType<>(DataTypes.UNTYPED_OBJECT);
        SimpleReference reference = new SimpleReference(
            referenceIdent,
            RowGranularity.DOC,
            dataType,
            ColumnPolicy.STRICT,
            IndexType.FULLTEXT,
            false,
            true,
            0,
            0,
            Literal.of(dataType, List.of(Map.of("f", 10))
            )
        );

        BytesStreamOutput out = new BytesStreamOutput();
        out.setVersion(Version.V_4_5_0);
        Reference.toStream(out, reference);

        StreamInput in = out.bytes().streamInput();
        in.setVersion(Version.V_4_5_0);
        Reference reference2 = Reference.fromStream(in);

        assertThat(reference2).isEqualTo(reference);
    }

    @Test
    public void test_mapping_generation_for_varchar_with_length() throws Exception {
        SQLExecutor e = SQLExecutor.builder(clusterService)
            .addTable("create table tbl (xs varchar(40))")
            .build();
        DocTableInfo table = e.resolveTableInfo("tbl");
        Reference reference = table.getReference(new ColumnIdent("xs"));
        Map<String, Object> mapping = reference.toMapping(reference.position());
        assertThat(mapping)
            .containsEntry("oid", 0L)
            .containsEntry("length_limit", 40)
            .containsEntry("position", 1)
            .containsEntry("type", "keyword")
            .hasSize(4);
        IndexMetadata indexMetadata = clusterService.state().metadata().indices().valuesIt().next();
        compareMappings(indexMetadata.mapping().sourceAsMap(), "xs", mapping);
    }

    @Test
    public void test_mapping_generation_for_string_without_doc_values() throws Exception {
        SQLExecutor e = SQLExecutor.builder(clusterService)
            .addTable("create table tbl (xs string storage with (columnstore = false))")
            .build();
        DocTableInfo table = e.resolveTableInfo("tbl");
        Reference reference = table.getReference(new ColumnIdent("xs"));
        Map<String, Object> mapping = reference.toMapping(reference.position());
        assertThat(mapping)
            .containsEntry("oid", 0L)
            .containsEntry("position", 1)
            .containsEntry("type", "keyword")
            .containsEntry("doc_values", "false")
            .hasSize(4);
        IndexMetadata indexMetadata = clusterService.state().metadata().indices().valuesIt().next();
        compareMappings(indexMetadata.mapping().sourceAsMap(), "xs", mapping);
    }

    @Test
    public void test_mapping_generation_for_float_without_doc_values() throws Exception {
        SQLExecutor e = SQLExecutor.builder(clusterService)
                .addTable("create table tbl (xs float storage with (columnstore = false))")
                .build();
        DocTableInfo table = e.resolveTableInfo("tbl");
        Reference reference = table.getReference(new ColumnIdent("xs"));
        Map<String, Object> mapping = reference.toMapping(reference.position());
        assertThat(mapping)
                .containsEntry("oid", 0L)
                .containsEntry("position", 1)
                .containsEntry("type", "float")
                .containsEntry("doc_values", "false")
                .hasSize(4);
        IndexMetadata indexMetadata = clusterService.state().metadata().indices().valuesIt().next();
        Map<String, Object> sourceAsMap = indexMetadata.mapping().sourceAsMap();
        compareMappings(indexMetadata.mapping().sourceAsMap(), "xs", mapping);
    }

    @Test
    public void test_mapping_generation_default_expression() throws Exception {
        SQLExecutor e = SQLExecutor.builder(clusterService)
            .addTable("create table tbl (xs string default 'foo')")
            .build();
        DocTableInfo table = e.resolveTableInfo("tbl");
        Reference reference = table.getReference(new ColumnIdent("xs"));
        Map<String, Object> mapping = reference.toMapping(reference.position());
        assertThat(mapping)
            .containsEntry("oid", 0L)
            .containsEntry("position", 1)
            .containsEntry("type", "keyword")
            .containsEntry("default_expr", "'foo'")
            .hasSize(4);

        IndexMetadata indexMetadata = clusterService.state().metadata().indices().valuesIt().next();
        compareMappings(indexMetadata.mapping().sourceAsMap(), "xs", mapping);
    }

    /**
     * Jackson optimizes writes of small long values as stores them as ints:
     * if (v > MIN_INT_AS_LONG) {
     *     return outputInt((int) v, b, off);
     * }
     * It makes sourceAsMap return int OIDS for small values.
     * We check OIDS by explicitly turning mapping oid to LONG.
     * This is done similar to how OID is handled in DocIndexMetadata.internalExtractColumnDefinitions
     */
    @SuppressWarnings("unchecked")
    static void compareMappings(Map<String, Object> sourceAsMap, String columnName, Map<String, Object> expected) {
        Map<String, Object> actual = (Map<String, Object>) Maps.getByPath(sourceAsMap, "properties." + columnName);
        assertThat(Long.valueOf(actual.remove("oid").toString())).isEqualTo(expected.remove("oid"));
        assertThat(actual).isEqualTo(expected);
    }
}
