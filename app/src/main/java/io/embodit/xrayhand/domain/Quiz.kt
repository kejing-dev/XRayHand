package io.embodit.xrayhand.domain

import kotlin.random.Random

enum class QuestionKind { LOCATE, IDENTIFY }

/** One question: [LOCATE] "point it out" (pinch the bone), [IDENTIFY] "which one is flashing" with three [options]. */
data class Question(val kind: QuestionKind, val boneId: String, val options: List<String> = emptyList())

data class Answer(val boneId: String, val picked: String, val correct: Boolean)

/**
 * The 10-question quiz (design `dataModel.quiz`): 4 carpals + 3 metacarpals + 3 phalanges, no bone twice;
 * 6 "point it out" and 4 "which is it" in a random order; the two wrong options of a "which is it" come from the
 * same group. A seed makes a run repeatable (debug `quiz seed <n>`).
 */
object QuizMaker {
    const val COUNT = 10

    fun make(catalog: BoneCatalog, seed: Long): List<Question> {
        val rnd = Random(seed)
        val picks = catalog.of(Group.CARPAL).shuffled(rnd).take(4) + catalog.of(Group.METACARPAL).shuffled(rnd).take(3) + catalog.of(Group.PHALANX).shuffled(rnd).take(3)
        val bones = picks.shuffled(rnd)
        val kinds = (List(6) { QuestionKind.LOCATE } + List(4) { QuestionKind.IDENTIFY }).shuffled(rnd)
        return bones.zip(kinds).map { (b, k) ->
            if (k == QuestionKind.LOCATE) {
                Question(k, b.id)
            } else {
                val wrong = catalog.of(b.group).filter { it.id != b.id }.shuffled(rnd).take(2).map { it.id }
                Question(k, b.id, (wrong + b.id).shuffled(rnd))
            }
        }
    }

    /** A pinch on [boneId] answers a LOCATE question; any hand counts (design: both hands are right). */
    fun check(q: Question, boneId: String): Boolean = q.boneId == boneId
}
