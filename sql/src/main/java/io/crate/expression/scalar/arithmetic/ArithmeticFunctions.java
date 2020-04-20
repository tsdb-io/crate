/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.expression.scalar.arithmetic;

import io.crate.expression.scalar.ScalarFunctionModule;
import io.crate.metadata.BaseFunctionResolver;
import io.crate.metadata.FunctionImplementation;
import io.crate.metadata.FunctionInfo;
import io.crate.metadata.functions.Signature;
import io.crate.metadata.functions.params.FuncParams;
import io.crate.metadata.functions.params.Param;
import io.crate.types.DataType;
import io.crate.types.DataTypes;

import java.util.List;
import java.util.function.BinaryOperator;

public class ArithmeticFunctions {

    private static final Param ARITHMETIC_TYPE = Param.of(
        DataTypes.NUMERIC_PRIMITIVE_TYPES, DataTypes.TIMESTAMPZ, DataTypes.TIMESTAMP, DataTypes.UNDEFINED);

    public static class Names {
        public static final String ADD = "add";
        public static final String SUBTRACT = "subtract";
        public static final String MULTIPLY = "multiply";
        public static final String DIVIDE = "divide";
        public static final String POWER = "power";
        public static final String MODULUS = "modulus";
        public static final String MOD = "mod";
    }

    static List<String> ops = List.of(Names.ADD,
                                      Names.SUBTRACT,
                                      Names.MULTIPLY,
                                      Names.DIVIDE,
                                      Names.MOD,
                                      Names.MODULUS);

    public static void register(ScalarFunctionModule module) {
        for (var op : ops) {
            for (var supportType : List.of(DataTypes.BYTE, DataTypes.SHORT, DataTypes.INTEGER)) {
                final BinaryOperator<Integer> o;
                switch (op) {
                    case Names.ADD:
                        o = Math::addExact;
                        break;
                    case Names.SUBTRACT:
                        o = Math::subtractExact;
                        break;

                    case Names.MULTIPLY:
                        o = Math::multiplyExact;
                        break;

                    case Names.DIVIDE:
                        o = (x, y) -> x / y;
                        break;

                    case Names.MOD:
                    case Names.MODULUS:
                        o = (x, y) -> x % y;
                        break;

                    default:
                        throw new IllegalStateException("Unexpected value: " + "");
                }
                module.register(
                    Signature.scalar(
                        op,
                        supportType.getTypeSignature(),
                        supportType.getTypeSignature(),
                        DataTypes.INTEGER.getTypeSignature()
                    ),
                    args -> new BinaryScalar<>(
                        o,
                        op,
                        DataTypes.INTEGER,
                        FunctionInfo.DETERMINISTIC_ONLY)
                );
            }

            for (var supportType : List.of(DataTypes.LONG, DataTypes.TIMESTAMPZ, DataTypes.TIMESTAMP)) {
                final BinaryOperator<Long> o;
                switch (op) {
                    case Names.ADD:
                        o = Math::addExact;
                        break;

                    case Names.SUBTRACT:
                        o = Math::subtractExact;
                        break;

                    case Names.MULTIPLY:
                        o = Math::multiplyExact;
                        break;

                    case Names.DIVIDE:
                        o = (x, y) -> x / y;
                        break;

                    case Names.MOD:
                    case Names.MODULUS:
                        o = (x, y) -> x % y;
                        break;

                    default:
                        throw new IllegalStateException("Unexpected value: " + "");
                }
                module.register(
                    Signature.scalar(
                        op,
                        supportType.getTypeSignature(),
                        supportType.getTypeSignature(),
                        DataTypes.INTEGER.getTypeSignature()
                    ),
                    args -> new BinaryScalar<>(
                        o,
                        op,
                        DataTypes.LONG,
                        FunctionInfo.DETERMINISTIC_ONLY)
                );
            }
            BinaryOperator<Double> o;
            switch (op) {
                case Names.ADD:
                    o = Double::sum;
                    break;

                case Names.SUBTRACT:
                    o = (x, y) -> x - y;
                    break;

                case Names.MULTIPLY:
                    o = (x, y) -> x * y;
                    break;

                case Names.DIVIDE:
                    o = (x, y) -> x / y;
                    break;

                case Names.MOD:
                case Names.MODULUS:
                    o = (x, y) -> x % y;
                    break;

                default:
                    throw new IllegalStateException("Unexpected value: " + "");
            }
            module.register(
                Signature.scalar(
                    op,
                    DataTypes.DOUBLE.getTypeSignature(),
                    DataTypes.DOUBLE.getTypeSignature(),
                    DataTypes.DOUBLE.getTypeSignature()
                ),
                args -> new BinaryScalar<>(
                    o,
                    op,
                    DataTypes.DOUBLE,
                    FunctionInfo.DETERMINISTIC_ONLY)
            );
            BinaryOperator<Float> opp;
            switch (op) {
                case Names.ADD:
                    opp = Float::sum;
                    break;
                case Names.SUBTRACT:
                    opp = (x, y) -> x - y;
                    break;

                case Names.MULTIPLY:
                    opp = (x, y) -> x * y;
                    break;

                case Names.DIVIDE:
                    opp = (x, y) -> x / y;
                    break;

                case Names.MOD:
                case Names.MODULUS:
                    opp = (x, y) -> x % y;
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + "");
            }
            module.register(
                Signature.scalar(
                    op,
                    DataTypes.FLOAT.getTypeSignature(),
                    DataTypes.FLOAT.getTypeSignature(),
                    DataTypes.FLOAT.getTypeSignature()
                ),
                args -> new BinaryScalar<>(
                    opp,
                    op,
                    DataTypes.FLOAT,
                    FunctionInfo.DETERMINISTIC_ONLY)
            );

            module.register(Names.POWER, new
                DoubleFunctionResolver(
                Names.POWER,
                Math::pow
            ));
        }
    }

    static final class DoubleFunctionResolver extends BaseFunctionResolver {

        private final String name;
        private final BinaryOperator<Double> doubleFunction;

        DoubleFunctionResolver(String name, BinaryOperator<Double> doubleFunction) {
            super(FuncParams.builder(ARITHMETIC_TYPE, ARITHMETIC_TYPE).build());
            this.name = name;
            this.doubleFunction = doubleFunction;
        }

        @Override
        public FunctionImplementation getForTypes(List<DataType> args) throws IllegalArgumentException {
            return new BinaryScalar<>(doubleFunction, name, DataTypes.DOUBLE, FunctionInfo.DETERMINISTIC_ONLY);
        }
    }
}
