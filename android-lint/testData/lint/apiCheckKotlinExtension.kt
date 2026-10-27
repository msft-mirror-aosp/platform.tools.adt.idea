package p1.p2

fun test(list: MutableList<String>) {
    list.<error descr="Call requires API level 35 (current min is 1): `java.util.List#removeFirst` (Prior to API level 35, this call would resolve to a Kotlin stdlib extension function. You can use `removeAt(0)` instead.)">removeFirst()</error>
    list.<error descr="Call requires API level 35 (current min is 1): `java.util.List#removeLast` (Prior to API level 35, this call would resolve to a Kotlin stdlib extension function. You can use `removeAt(list.lastIndex)` instead.)">remove<caret>Last()</error>
}
