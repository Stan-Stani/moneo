package com.poketrek.moneo.reading

/**
 * Finds Pokémon, move, ability and trainer-class names in a message. Battle
 * text is mostly templates filled in at runtime ("{이상해씨}의
 * {몸통박치기}!"), so the dialog index has little to match, but the names
 * are exactly the words worth showing.
 *
 * A name counts only as the start of a word (particles attach to the end:
 * 이상해씨의, 구구가), longest first, which keeps short names from matching
 * inside unrelated words.
 */
class NameFinder(names: Collection<String>) {
    private val byFirst: Map<Char, List<String>> = names
        .filter { it.length >= 2 }
        .distinct()
        .groupBy { it[0] }
        .mapValues { (_, v) -> v.sortedByDescending { it.length } }

    fun find(message: String): List<String> {
        val out = LinkedHashSet<String>()
        for (word in WORD.findAll(message)) {
            val w = word.value
            byFirst[w[0]]?.firstOrNull { w.startsWith(it) }?.let { out += it }
        }
        return out.toList()
    }

    companion object {
        val EMPTY = NameFinder(emptyList())
        private val WORD = Regex("[가-힣]+")
        /** VocabEntry.primarySourceType values that are names filled into templates. */
        val NAME_TYPES = setOf("pokemon_species", "pokemon_move", "pokemon_ability", "trainer_class_name")
    }
}
