package lava.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-bluff unit tests for [mapInstanceOf].
 *
 * [mapInstanceOf] is used across the app to pull out one variant of a sealed
 * hierarchy and transform it in a single pass (filter-by-type then map). The
 * tests assert on the exact transformed output list — order, contents, and the
 * fact that non-matching elements are dropped — which is the behavior any caller
 * relies on. No mocking: the real reified inline function is invoked on real
 * lists of a real sealed hierarchy.
 */
class CollectionsTest {

    private sealed interface Shape
    private data class Circle(val radius: Int) : Shape
    private data class Square(val side: Int) : Shape

    @Test
    fun `keeps only the requested subtype and transforms each kept element`() {
        val shapes: List<Shape> = listOf(
            Circle(1),
            Square(10),
            Circle(2),
            Square(20),
            Circle(3),
        )

        val radii: List<Int> = shapes.mapInstanceOf<Shape, Circle, Int> { it.radius }

        // Squares are dropped; circles are transformed in encounter order.
        assertEquals(listOf(1, 2, 3), radii)
    }

    @Test
    fun `returns empty list when no element matches the subtype`() {
        val shapes: List<Shape> = listOf(Square(1), Square(2))

        val radii: List<Int> = shapes.mapInstanceOf<Shape, Circle, Int> { it.radius }

        assertTrue(radii.isEmpty())
    }

    @Test
    fun `returns empty list for an empty source`() {
        val shapes: List<Shape> = emptyList()

        val result: List<Int> = shapes.mapInstanceOf<Shape, Circle, Int> { it.radius }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `preserves duplicate matches and their transform values`() {
        val shapes: List<Shape> = listOf(Circle(5), Circle(5), Square(1), Circle(7))

        val radii: List<Int> = shapes.mapInstanceOf<Shape, Circle, Int> { it.radius }

        assertEquals(listOf(5, 5, 7), radii)
    }

    @Test
    fun `transform can change the result element type`() {
        val shapes: List<Shape> = listOf(Square(4), Circle(9), Square(16))

        val labels: List<String> = shapes.mapInstanceOf<Shape, Square, String> { "side=${it.side}" }

        assertEquals(listOf("side=4", "side=16"), labels)
    }
}
