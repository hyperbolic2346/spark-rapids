# Copyright (c) 2021-2022, NVIDIA CORPORATION.
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

from asserts import assert_gpu_and_cpu_are_equal_collect, assert_gpu_and_cpu_error
from data_gen import *
from pyspark.sql.types import *
from spark_session import with_cpu_session
from string_test import mk_str_gen
import pyspark.sql.functions as f

nested_gens = [ArrayGen(LongGen()), ArrayGen(decimal_gen_38_10),
               StructGen([("a", LongGen()), ("b", decimal_gen_38_10)]),
               MapGen(StringGen(pattern='key_[0-9]', nullable=False), StringGen())]
# additional test for NonNull Array because of https://github.com/rapidsai/cudf/pull/8181
non_nested_array_gens = [ArrayGen(sub_gen, nullable=nullable)
                         for nullable in [True, False]
                         for sub_gen in all_gen + [null_gen]]
non_nested_array_gens_dec128 = [ArrayGen(sub_gen, nullable=nullable)
                                for nullable in [True, False]
                                for sub_gen in all_gen + [null_gen] + decimal_128_gens_no_neg]

@pytest.mark.parametrize('data_gen', non_nested_array_gens_dec128, ids=idfn)
def test_concat_list(data_gen):
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: binary_op_df(spark, data_gen).selectExpr('concat(a)'))

    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: binary_op_df(spark, data_gen).selectExpr('concat(a, b)'))

    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: three_col_df(spark, data_gen, data_gen, data_gen
                                   ).selectExpr('concat(a, b, c)'))

def test_empty_concat_list():
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: binary_op_df(spark, ArrayGen(LongGen())).selectExpr('concat()'))

@pytest.mark.parametrize('data_gen', non_nested_array_gens_dec128, ids=idfn)
def test_concat_list_with_lit(data_gen):
    array_lit = gen_scalar(data_gen)
    array_lit2 = gen_scalar(data_gen)
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: binary_op_df(spark, data_gen).select(
            f.concat(f.col('a'),
                     f.col('b'),
                     f.lit(array_lit).cast(data_gen.data_type))))

    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: binary_op_df(spark, data_gen).select(
            f.concat(f.lit(array_lit).cast(data_gen.data_type),
                     f.col('a'),
                     f.lit(array_lit2).cast(data_gen.data_type))))

    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: binary_op_df(spark, data_gen).select(
            f.concat(f.lit(array_lit).cast(data_gen.data_type),
                     f.lit(array_lit2).cast(data_gen.data_type))))

def test_concat_string():
    gen = mk_str_gen('.{0,5}')
    (s1, s2) = gen_scalars(gen, 2, force_no_nulls=True)
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: binary_op_df(spark, gen).select(
                f.concat(),
                f.concat(f.col('a')),
                f.concat(s1),
                f.concat(f.col('a'), f.col('b')),
                f.concat(f.col('a'), f.col('b'), f.col('a')),
                f.concat(s1, f.col('b')),
                f.concat(f.col('a'), s2),
                f.concat(f.lit(None).cast('string'), f.col('b')),
                f.concat(f.col('a'), f.lit(None).cast('string')),
                f.concat(f.lit(''), f.col('b')),
                f.concat(f.col('a'), f.lit(''))))

@pytest.mark.parametrize('data_gen', all_gen + decimal_128_gens_no_neg + nested_gens, ids=idfn)
@pytest.mark.parametrize('size_of_null', ['true', 'false'], ids=idfn)
def test_size_of_array(data_gen, size_of_null):
    gen = ArrayGen(data_gen)
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr('size(a)'),
            conf={'spark.sql.legacy.sizeOfNull': size_of_null})

@pytest.mark.parametrize('data_gen', map_gens_sample, ids=idfn)
@pytest.mark.parametrize('size_of_null', ['true', 'false'], ids=idfn)
def test_size_of_map(data_gen, size_of_null):
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, data_gen).selectExpr('size(a)'),
            conf={'spark.sql.legacy.sizeOfNull': size_of_null})

@pytest.mark.parametrize('data_gen', non_nested_array_gens_dec128, ids=idfn)
@pytest.mark.parametrize('is_ascending', [True, False], ids=idfn)
def test_sort_array(data_gen, is_ascending):
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: unary_op_df(spark, data_gen).select(
            f.sort_array(f.col('a'), is_ascending)))

@pytest.mark.parametrize('data_gen', non_nested_array_gens_dec128, ids=idfn)
@pytest.mark.parametrize('is_ascending', [True, False], ids=idfn)
def test_sort_array_lit(data_gen, is_ascending):
    array_lit = gen_scalar(data_gen)
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: unary_op_df(spark, data_gen, length=10).select(
            f.sort_array(f.lit(array_lit), is_ascending)))

# For functionality test, the sequence length in each row should be limited,
# to avoid the exception as below,
#     "Too long sequence: 2147483745. Should be <= 2147483632"
# And the input data should follow the rules below,
#        (step > 0 && start <= stop)
#     or (step < 0 && start >= stop)
#     or (step == 0 && start == stop)
sequence_normal_integral_gens = [
    # (step > 0 && start <= stop)
    (ByteGen(min_val=-10, max_val=20, special_cases=[]),
        ByteGen(min_val=20, max_val=50, special_cases=[]),
        ByteGen(min_val=1, max_val=5, special_cases=[])),
    (ShortGen(min_val=-10, max_val=20, special_cases=[]),
        ShortGen(min_val=20, max_val=50, special_cases=[]),
        ShortGen(min_val=1, max_val=5, special_cases=[])),
    (IntegerGen(min_val=-10, max_val=20, special_cases=[]),
        IntegerGen(min_val=20, max_val=50, special_cases=[]),
        IntegerGen(min_val=1, max_val=5, special_cases=[])),
    (LongGen(min_val=-10, max_val=20, special_cases=[None]),
        LongGen(min_val=20, max_val=50, special_cases=[None]),
        LongGen(min_val=1, max_val=5, special_cases=[None])),
    # (step < 0 && start >= stop)
    (ByteGen(min_val=20, max_val=50, special_cases=[]),
        ByteGen(min_val=-10, max_val=20, special_cases=[]),
        ByteGen(min_val=-5, max_val=-1, special_cases=[])),
    (ShortGen(min_val=20, max_val=50, special_cases=[]),
        ShortGen(min_val=-10, max_val=20, special_cases=[]),
        ShortGen(min_val=-5, max_val=-1, special_cases=[])),
    (IntegerGen(min_val=20, max_val=50, special_cases=[]),
        IntegerGen(min_val=-10, max_val=20, special_cases=[]),
        IntegerGen(min_val=-5, max_val=-1, special_cases=[])),
    (LongGen(min_val=20, max_val=50, special_cases=[None]),
        LongGen(min_val=-10, max_val=20, special_cases=[None]),
        LongGen(min_val=-5, max_val=-1, special_cases=[None])),
    # (step == 0 && start == stop)
    (ByteGen(min_val=20, max_val=20, special_cases=[]),
        ByteGen(min_val=20, max_val=20, special_cases=[]),
        ByteGen(min_val=0, max_val=0, special_cases=[])),
    (ShortGen(min_val=20, max_val=20, special_cases=[]),
        ShortGen(min_val=20, max_val=20, special_cases=[]),
        ShortGen(min_val=0, max_val=0, special_cases=[])),
    (IntegerGen(min_val=20, max_val=20, special_cases=[]),
        IntegerGen(min_val=20, max_val=20, special_cases=[]),
        IntegerGen(min_val=0, max_val=0, special_cases=[])),
    (LongGen(min_val=20, max_val=20, special_cases=[None]),
        LongGen(min_val=20, max_val=20, special_cases=[None]),
        LongGen(min_val=0, max_val=0, special_cases=[None])),
]

sequence_normal_no_step_integral_gens = [(gens[0], gens[1]) for
    gens in sequence_normal_integral_gens]

@pytest.mark.parametrize('start_gen,stop_gen', sequence_normal_no_step_integral_gens, ids=idfn)
def test_sequence_without_step(start_gen, stop_gen):
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: two_col_df(spark, start_gen, stop_gen).selectExpr(
            "sequence(a, b)",
            "sequence(a, 20)",
            "sequence(20, b)"))

@pytest.mark.parametrize('start_gen,stop_gen,step_gen', sequence_normal_integral_gens, ids=idfn)
def test_sequence_with_step(start_gen, stop_gen, step_gen):
    # Get a step scalar from the 'step_gen' which follows the rules.
    step_gen.start(random.Random(0))
    step_lit = step_gen.gen()
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: three_col_df(spark, start_gen, stop_gen, step_gen).selectExpr(
            "sequence(a, b, c)",
            "sequence(a, b, {})".format(step_lit),
            "sequence(a, 20, c)",
            "sequence(a, 20, {})".format(step_lit),
            "sequence(20, b, c)",
            "sequence(20, 20, c)",
            "sequence(20, b, {})".format(step_lit)))

# Illegal sequence boundaries:
#     step > 0, but start > stop
#     step < 0, but start < stop
#     step == 0, but start != stop
#
# All integral types share the same check implementation, so each case
# will not run over all the types in the tests.
sequence_illegal_boundaries_integral_gens = [
    # step > 0, but start > stop
    (ShortGen(min_val=20, max_val=50, special_cases=[]),
        ShortGen(min_val=-10, max_val=19, special_cases=[]),
        ShortGen(min_val=1, max_val=5, special_cases=[])),
    (LongGen(min_val=20, max_val=50, special_cases=[None]),
        LongGen(min_val=-10, max_val=19, special_cases=[None]),
        LongGen(min_val=1, max_val=5, special_cases=[None])),
    # step < 0, but start < stop
    (ByteGen(min_val=-10, max_val=19, special_cases=[]),
        ByteGen(min_val=20, max_val=50, special_cases=[]),
        ByteGen(min_val=-5, max_val=-1, special_cases=[])),
    (IntegerGen(min_val=-10, max_val=19, special_cases=[]),
        IntegerGen(min_val=20, max_val=50, special_cases=[]),
        IntegerGen(min_val=-5, max_val=-1, special_cases=[])),
    # step == 0, but start != stop
    (IntegerGen(min_val=-10, max_val=19, special_cases=[]),
        IntegerGen(min_val=20, max_val=50, special_cases=[]),
        IntegerGen(min_val=0, max_val=0, special_cases=[]))
]

@pytest.mark.parametrize('start_gen,stop_gen,step_gen', sequence_illegal_boundaries_integral_gens, ids=idfn)
def test_sequence_illegal_boundaries(start_gen, stop_gen, step_gen):
    assert_gpu_and_cpu_error(
        lambda spark:three_col_df(spark, start_gen, stop_gen, step_gen).selectExpr(
            "sequence(a, b, c)").collect(),
        conf = {}, error_message = "Illegal sequence boundaries")

# Exceed the max length of a sequence
#     "Too long sequence: xxxxxxxxxx. Should be <= 2147483632"
sequence_too_long_length_gens = [
    IntegerGen(min_val=2147483633, max_val=2147483633, special_cases=[]),
    LongGen(min_val=2147483635, max_val=2147483635, special_cases=[None])
]

@pytest.mark.parametrize('stop_gen', sequence_too_long_length_gens, ids=idfn)
def test_sequence_too_long_sequence(stop_gen):
    assert_gpu_and_cpu_error(
        # To avoid OOM, reduce the row number to 1, it is enough to verify this case.
        lambda spark:unary_op_df(spark, stop_gen, 1).selectExpr(
            "sequence(0, a)").collect(),
        conf = {}, error_message = "Too long sequence")

def get_sequence_cases_mixed_df(spark, length=2048):
    # Generate the sequence data following the 3 rules mixed in a single dataset.
    #     (step > num.zero && start <= stop) ||
    #     (step < num.zero && start >= stop) ||
    #     (step == num.zero && start == stop)
    data_gen = IntegerGen(nullable=False, min_val=-10, max_val=10, special_cases=[])
    def get_sequence_data(gen, len):
        gen.start(random.Random(0))
        list = []
        for index in range(len):
            start = gen.gen()
            stop = gen.gen()
            step = gen.gen()
            # decide the direction of step
            if start < stop:
                step = abs(step) + 1
            elif start == stop:
                step = 0
            else:
                step = -(abs(step) + 1)
            list.append(tuple([start, stop, step]))
        # add special case
        list.append(tuple([2, 2, 0]))
        return list

    mixed_schema = StructType([
        StructField('a', data_gen.data_type),
        StructField('b', data_gen.data_type),
        StructField('c', data_gen.data_type)])
    return spark.createDataFrame(
        SparkContext.getOrCreate().parallelize(get_sequence_data(data_gen, length)),
        mixed_schema)

# test for 3 cases mixed in a single dataset
def test_sequence_with_step_mixed_cases():
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: get_sequence_cases_mixed_df(spark)
            .selectExpr("sequence(a, b, c)"))
