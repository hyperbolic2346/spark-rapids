# Copyright (c) 2020-2021, NVIDIA CORPORATION.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import pytest

from asserts import assert_gpu_and_cpu_are_equal_collect, assert_gpu_and_cpu_are_equal_sql
from conftest import is_dataproc_runtime
from data_gen import *
from pyspark.sql.types import *

def test_struct_scalar_project():
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark : spark.range(2).selectExpr(
                "named_struct('1', 2, '3', 4) as i", 
                "named_struct('a', 'b', 'c', 'd', 'e', named_struct()) as s",
                "named_struct('a', map('foo', 10, 'bar', 11), 'arr', array(1.0, 2.0, 3.0)) as st"
                "id"))

@pytest.mark.parametrize('data_gen', [StructGen([["first", boolean_gen], ["second", byte_gen], ["third", float_gen]]),
    StructGen([["first", short_gen], ["second", int_gen], ["third", long_gen]]),
    StructGen([["first", double_gen], ["second", date_gen], ["third", timestamp_gen]]),
    StructGen([["first", string_gen], ["second", ArrayGen(byte_gen)], ["third", simple_string_to_string_map_gen]]),
    StructGen([["first", decimal_gen_default], ["second", decimal_gen_scale_precision], ["third", decimal_gen_same_scale_precision]]),
    StructGen([["first", decimal_gen_20_2], ["second", decimal_gen_30_2], ["third", decimal_gen_36_5]]),
    StructGen([["first", decimal_gen_20_2], ["second", decimal_gen_30_2], ["third", decimal_gen_36_neg5]])], ids=idfn)
def test_struct_get_item(data_gen):
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark : unary_op_df(spark, data_gen).selectExpr(
                'a.first',
                'a.second',
                'a.third'), conf=allow_negative_scale_of_decimal_conf)


@pytest.mark.parametrize('data_gen', all_basic_gens + [null_gen, decimal_gen_default,
                                                       decimal_gen_scale_precision] + decimal_128_gens + single_level_array_gens + struct_gens_sample + map_gens_sample, ids=idfn)
def test_make_struct(data_gen):
    # Spark has no good way to create a map literal without the map function
    # so we are inserting one.
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark : binary_op_df(spark, data_gen).selectExpr(
                'struct(a, b)',
                'named_struct("foo", b, "m", map("a", "b"), "n", null, "bar", 5, "other", named_struct("z", "z"),"end", a)'),
            conf = allow_negative_scale_of_decimal_conf)


@pytest.mark.parametrize('data_gen', [StructGen([["first", boolean_gen], ["second", byte_gen], ["third", float_gen]]),
                                      StructGen([["first", short_gen], ["second", int_gen], ["third", long_gen]]),
                                      StructGen([["first", long_gen], ["second", long_gen], ["third", long_gen]]),
                                      StructGen([["first", string_gen], ["second", ArrayGen(string_gen)], ["third", ArrayGen(string_gen)]])], ids=idfn)
def test_orderby_struct(data_gen):
    assert_gpu_and_cpu_are_equal_sql(
        lambda spark : append_unique_int_col_to_df(spark, unary_op_df(spark, data_gen)),
        'struct_table',
        'select struct_table.a, struct_table.uniq_int from struct_table order by uniq_int')


@pytest.mark.parametrize('data_gen', [StructGen([["first", string_gen], ["second", ArrayGen(string_gen)], ["third", ArrayGen(string_gen)]])], ids=idfn)
def test_orderby_struct_2(data_gen):
    assert_gpu_and_cpu_are_equal_sql(
        lambda spark : append_unique_int_col_to_df(spark, unary_op_df(spark, data_gen)),
        'struct_table',
        'select struct_table.a, struct_table.uniq_int from struct_table order by uniq_int')
