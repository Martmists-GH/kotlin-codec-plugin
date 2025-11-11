package com.martmists.serialization

@Record
data class TestData(
    val x: Int,
    val y: String,
)

@Record
data class CollectionData(
    val items: List<TestData>
)
