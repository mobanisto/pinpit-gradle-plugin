/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.windows

import org.w3c.dom.Element

fun Element.createChild(tagName: String, block: Element.() -> Unit = {}): Element {
    val fragment = ownerDocument.createElement(tagName).apply(block)
    return appendChild(fragment) as Element
}

fun Element.createChild(tagName: String, id: String, block: Element.() -> Unit = {}): Element {
    val fragment = createChild(tagName).apply {
        setAttribute("Id", id)
        block()
    }
    return appendChild(fragment) as Element
}
