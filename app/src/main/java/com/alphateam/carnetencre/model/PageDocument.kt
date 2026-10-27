package com.alphateam.carnetencre.model

import android.graphics.RectF

/**
 * État en mémoire d'une page ouverte : éléments ordonnés par z, zone sélectionnée,
 * et piles annuler / rétablir illimitées (remises à zéro quand on change de page).
 */
class PageDocument(val pageId: Long, initial: List<PageElement>, height: Float = PAGE_H) {

    /** Hauteur de la page en unités : elle ne fait que grandir (réponses de Claude, page infinie). */
    var height: Float = maxOf(height, PAGE_H)
        private set

    /** Allonge la page jusqu'à [h] au moins. Renvoie vrai si elle a grandi. */
    fun growTo(h: Float): Boolean {
        if (h <= height) return false
        height = h
        revision++
        return true
    }

    private sealed class Op {
        class Add(val items: List<PageElement>) : Op()
        class Remove(val items: MutableList<PageElement>) : Op()
        class Move(val block: Block, val dx: Float, val dy: Float) : Op()
        class Replace(val before: List<PageElement>, val after: List<PageElement>) : Op()
    }

    private val items = ArrayList<PageElement>(initial.sortedBy { it.z })
    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()

    /** Zone rectangle (unités de page) : si elle existe, Claude ne lit que ce cadre. */
    var zone: RectF? = null

    /** Incrémenté à chaque modification du contenu ; sert à déclencher la sauvegarde. */
    var revision = 0
        private set

    val elements: List<PageElement> get() = items
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun nextZ(): Int = (items.maxOfOrNull { it.z } ?: 0) + 1

    fun add(element: PageElement) = push(Op.Add(listOf(element)))

    /**
     * Retire des éléments. Avec [continuing], ils rejoignent la dernière suppression : un
     * même geste de gomme s'annule en une seule fois.
     */
    fun remove(elements: List<PageElement>, continuing: Boolean = false) {
        val present = elements.filter { it in items }
        if (present.isEmpty()) return
        val last = undoStack.lastOrNull()
        if (continuing && last is Op.Remove && redoStack.isEmpty()) {
            items.removeAll(present.toSet())
            last.items.addAll(present)
            revision++
        } else {
            push(Op.Remove(present.toMutableList()))
        }
    }

    /** Ajoute plusieurs éléments en une seule étape d'annulation (sélection dupliquée). */
    fun addAll(elements: List<PageElement>) {
        if (elements.isNotEmpty()) push(Op.Add(elements))
    }

    /**
     * Remplace des éléments par leurs versions transformées (déplacées, redimensionnées,
     * recolorées), en une seule étape d'annulation.
     */
    fun replace(before: List<PageElement>, after: List<PageElement>) {
        val present = before.filter { it in items }
        if (present.isEmpty() && after.isEmpty()) return
        push(Op.Replace(present, after))
    }

    /** Le bloc a déjà été déplacé pendant le glissé : on n'enregistre que l'opération. */
    fun recordMove(block: Block, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        undoStack.addLast(Op.Move(block, dx, dy))
        redoStack.clear()
        revision++
    }

    fun undo(): Boolean {
        val op = undoStack.removeLastOrNull() ?: return false
        revert(op)
        redoStack.addLast(op)
        revision++
        return true
    }

    fun redo(): Boolean {
        val op = redoStack.removeLastOrNull() ?: return false
        apply(op)
        undoStack.addLast(op)
        revision++
        return true
    }

    /** Bas du contenu le plus bas de la page, ou null si la page est vide. */
    fun contentBottom(): Float? = items.maxOfOrNull { it.bounds().bottom }

    fun blockAt(x: Float, y: Float): Block? =
        items.asReversed().firstOrNull { it is Block && it.contains(x, y) } as Block?

    private fun push(op: Op) {
        apply(op)
        undoStack.addLast(op)
        redoStack.clear()
        revision++
    }

    private fun apply(op: Op) {
        when (op) {
            is Op.Add -> op.items.forEach { insertSorted(it) }
            is Op.Remove -> items.removeAll(op.items.toSet())
            is Op.Move -> { op.block.x += op.dx; op.block.y += op.dy }
            is Op.Replace -> {
                items.removeAll(op.before.toSet())
                op.after.forEach { insertSorted(it) }
            }
        }
    }

    private fun revert(op: Op) {
        when (op) {
            is Op.Add -> items.removeAll(op.items.toSet())
            is Op.Remove -> op.items.forEach { insertSorted(it) }
            is Op.Move -> { op.block.x -= op.dx; op.block.y -= op.dy }
            is Op.Replace -> {
                items.removeAll(op.after.toSet())
                op.before.forEach { insertSorted(it) }
            }
        }
    }

    private fun insertSorted(e: PageElement) {
        val idx = items.indexOfFirst { it.z > e.z }
        if (idx < 0) items.add(e) else items.add(idx, e)
    }
}
