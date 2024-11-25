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

package io.crate.expression.tablefunctions;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.ThrowingConsumer;
import org.junit.Test;

import io.crate.metadata.Scalar;


public class MatchesFunctionTest extends AbstractTableFunctionsTest {

    @Test
    public void test_compile_creates_regex_matcher_instance_on_table_function() throws Exception {
        ThrowingConsumer<Scalar<?, ?>> matcher = func -> {
            assertThat(func).isExactlyInstanceOf(MatchesFunction.class);
            assertThat(((MatchesFunction) func).pattern()).isNotNull();
        };
        assertCompile("regexp_matches(name, '.*(ba).*')", matcher);
    }

    @Test
    public void test_execute_with_flags() throws Exception {
        assertExecute("regexp_matches('foobarbequebaz bar', '.*(ba).', 'us')", "[ba]\n");
    }

    @Test
    public void test_when_there_are_no_matches_the_result_is_an_empty_table() {
        assertExecute("regexp_matches('foobar', '^(a(.+)z)$')", "");
    }

    @Test
    public void test_execute_with_g_flag() throws Exception {
        assertExecute("regexp_matches('#abc #abc   #def #def #ghi #ghi', '(#[^\\s]*) (#[^\\s]*)', 'g')",
            """
                [#abc, #abc]
                [#def, #def]
                [#ghi, #ghi]
                """);
        assertExecute("regexp_matches('#abc #abc   #def #def #ghi #ghi', '#[^\\s]* #[^\\s]*', 'g')",
            """
                [#abc #abc]
                [#def #def]
                [#ghi #ghi]
                """);
    }

    @Test
    public void test_execute_without_g_flag() throws Exception {
        assertExecute("regexp_matches('#abc #abc   #def #def #ghi #ghi', '(#[^\\s]*) (#[^\\s]*)')",
                      "[#abc, #abc]\n");
        assertExecute("regexp_matches('#abc #abc   #def #def #ghi #ghi', '#[^\\s]* #[^\\s]*')",
                      "[#abc #abc]\n");
    }

    @Test
    public void test_execute_postgres_example() {
        assertExecute("regexp_matches('foobarbequebazilbarfbonk', '(b[^b]+)(b[^b]+)', 'g')",
            """
                [bar, beque]
                [bazil, barf]
                """);
    }

    @Test
    public void test_matches_null_group() throws Exception {
        assertExecute("regexp_matches('gcc -Wall --std=c99 -o source source.c', '\\w+( --?\\w+)*( \\w+)*', 'g')",
            """
                [ --std, NULL]
                [ -o,  source]
                [NULL, NULL]
                """);
    }
}
