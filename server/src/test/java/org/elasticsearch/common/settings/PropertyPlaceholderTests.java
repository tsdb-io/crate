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

package org.elasticsearch.common.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.elasticsearch.test.ESTestCase;
import org.junit.Test;

public class PropertyPlaceholderTests extends ESTestCase {

    @Test
    public void testSimple() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("{", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("foo1", "bar1");
        map.put("foo2", "bar2");
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        assertThat(propertyPlaceholder.replacePlaceholders("{foo1}", placeholderResolver)).isEqualTo("bar1");
        assertThat(propertyPlaceholder.replacePlaceholders("a {foo1}b", placeholderResolver)).isEqualTo("a bar1b");
        assertThat(propertyPlaceholder.replacePlaceholders("{foo1}{foo2}", placeholderResolver)).isEqualTo("bar1bar2");
        assertThat(propertyPlaceholder.replacePlaceholders("a {foo1} b {foo2} c", placeholderResolver)).isEqualTo("a bar1 b bar2 c");
    }

    @Test
    public void testVariousPrefixSuffix() {
        // Test various prefix/suffix lengths
        PropertyPlaceholder ppEqualsPrefix = new PropertyPlaceholder("{", "}", false);
        PropertyPlaceholder ppLongerPrefix = new PropertyPlaceholder("${", "}", false);
        PropertyPlaceholder ppShorterPrefix = new PropertyPlaceholder("{", "}}", false);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("foo", "bar");
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        assertThat(ppEqualsPrefix.replacePlaceholders("{foo}", placeholderResolver)).isEqualTo("bar");
        assertThat(ppLongerPrefix.replacePlaceholders("${foo}", placeholderResolver)).isEqualTo("bar");
        assertThat(ppShorterPrefix.replacePlaceholders("{foo}}", placeholderResolver)).isEqualTo("bar");
    }

    @Test
    public void testDefaultValue() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("${", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        assertThat(propertyPlaceholder.replacePlaceholders("${foo:bar}", placeholderResolver)).isEqualTo("bar");
        assertThat(propertyPlaceholder.replacePlaceholders("${foo:}", placeholderResolver)).isEqualTo("");
    }

    @Test
    public void testIgnoredUnresolvedPlaceholder() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("${", "}", true);
        Map<String, String> map = new LinkedHashMap<>();
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        assertThat(propertyPlaceholder.replacePlaceholders("${foo}", placeholderResolver)).isEqualTo("${foo}");
    }

    @Test
    public void testNotIgnoredUnresolvedPlaceholder() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("${", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        try {
            propertyPlaceholder.replacePlaceholders("${foo}", placeholderResolver);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).isEqualTo("Could not resolve placeholder 'foo'");
        }
    }

    @Test
    public void testShouldIgnoreMissing() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("${", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, true, true);
        assertThat(propertyPlaceholder.replacePlaceholders("bar${foo}", placeholderResolver)).isEqualTo("bar");
    }

    @Test
    public void testRecursive() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("${", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("foo", "${foo1}");
        map.put("foo1", "${foo2}");
        map.put("foo2", "bar");
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        assertThat(propertyPlaceholder.replacePlaceholders("${foo}", placeholderResolver)).isEqualTo("bar");
        assertThat(propertyPlaceholder.replacePlaceholders("a${foo}b", placeholderResolver)).isEqualTo("abarb");
    }

    @Test
    public void testNestedLongerPrefix() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("${", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("foo", "${foo1}");
        map.put("foo1", "${foo2}");
        map.put("foo2", "bar");
        map.put("barbar", "baz");
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        assertThat(propertyPlaceholder.replacePlaceholders("${bar${foo}}", placeholderResolver)).isEqualTo("baz");
    }

    @Test
    public void testNestedSameLengthPrefixSuffix() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("{", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("foo", "{foo1}");
        map.put("foo1", "{foo2}");
        map.put("foo2", "bar");
        map.put("barbar", "baz");
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        assertThat(propertyPlaceholder.replacePlaceholders("{bar{foo}}", placeholderResolver)).isEqualTo("baz");
    }

    @Test
    public void testNestedShorterPrefix() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("{", "}}", false);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("foo", "{foo1}}");
        map.put("foo1", "{foo2}}");
        map.put("foo2", "bar");
        map.put("barbar", "baz");
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        assertThat(propertyPlaceholder.replacePlaceholders("{bar{foo}}}}", placeholderResolver)).isEqualTo("baz");
    }

    @Test
    public void testCircularReference() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("${", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("foo", "${bar}");
        map.put("bar", "${foo}");
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, false, true);
        try {
            propertyPlaceholder.replacePlaceholders("${foo}", placeholderResolver);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).isEqualTo("Circular placeholder reference 'foo' in property definitions");
        }
    }

    @Test
    public void testShouldRemoveMissing() {
        PropertyPlaceholder propertyPlaceholder = new PropertyPlaceholder("${", "}", false);
        Map<String, String> map = new LinkedHashMap<>();
        PropertyPlaceholder.PlaceholderResolver placeholderResolver = new SimplePlaceholderResolver(map, true, false);
        assertThat(propertyPlaceholder.replacePlaceholders("bar${foo}", placeholderResolver)).isEqualTo("bar${foo}");
    }

    private class SimplePlaceholderResolver implements PropertyPlaceholder.PlaceholderResolver {
        private Map<String, String> map;
        private boolean shouldIgnoreMissing;
        private boolean shouldRemoveMissing;

        SimplePlaceholderResolver(Map<String, String> map, boolean shouldIgnoreMissing, boolean shouldRemoveMissing) {
            this.map = map;
            this.shouldIgnoreMissing = shouldIgnoreMissing;
            this.shouldRemoveMissing = shouldRemoveMissing;
        }

        @Override
        public String resolvePlaceholder(String placeholderName) {
            return map.get(placeholderName);
        }

        @Override
        public boolean shouldIgnoreMissing(String placeholderName) {
            return shouldIgnoreMissing;
        }

        @Override
        public boolean shouldRemoveMissingPlaceholder(String placeholderName) {
            return shouldRemoveMissing;
        }
    }
}
