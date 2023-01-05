/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.windows

import de.mobanisto.pinpit.desktop.application.internal.files.visitFileTree
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Stack
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.name

class GenerateFilesWxs(private val output: Path, private val dir: Path, private val defaultDirectoryName: String) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(GenerateFilesWxs::class.java)
    }

    fun execute(): List<FileEntry> {
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = documentBuilder.newDocument()

        val executables = createDocument(doc, dir)

        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        val source = DOMSource(doc)
        val os = Files.newOutputStream(output)
        os.use {
            val result = StreamResult(it)
            transformer.transform(source, result)
        }

        return executables
    }

    private fun createDocument(doc: Document, dir: Path): List<FileEntry> {
        val wix = doc.createElement("Wix").apply {
            setAttribute("xmlns", "http://schemas.microsoft.com/wix/2006/wi")
            doc.appendChild(this)
        }
        val fragment = wix.createChild("Fragment", "FragmentFiles")
        val targetDir = fragment.createChild("DirectoryRef", "TARGETDIR")
        val programFiles = targetDir.createChild("Directory", "ProgramFiles64Folder")
        val installDir = programFiles.createChild("Directory", "INSTALLDIR") {
            setAttribute("Name", defaultDirectoryName)
        }
        val fileEntries = buildFileTree(installDir, dir)

        val componentGroup = fragment.createChild("ComponentGroup", "Files")
        for (fileEntry in fileEntries) {
            componentGroup.createChild("ComponentRef", fileEntry.id)
        }
        val executables = mutableListOf<FileEntry>()
        for (fileEntry in fileEntries) {
            val file = fileEntry.file
            if (file.nameCount == 1 && file.name.endsWith(".exe")) {
                executables.add(fileEntry)
            }
        }
        return executables
    }

    private fun buildFileTree(installDir: Element, dir: Path): List<FileEntry> {
        val stack = Stack<Element>()
        stack.push(installDir)
        var current = dir
        var fileId = 1
        val list = mutableListOf<FileEntry>()
        dir.visitFileTree {
            onPreVisitDirectory { directory, _ ->
                val relative = current.relativize(directory)
                if (relative.toString().isEmpty()) {
                    return@onPreVisitDirectory FileVisitResult.CONTINUE
                }
                val indent = "  ".repeat(stack.size - 1)
                logger.debug("${indent}directory: $relative")
                val uuid = UUID.randomUUID()
                stack.peek().createChild("Directory", fileIdFromUUID(uuid)) {
                    setAttribute("Name", relative.toString())
                    stack.push(this)
                }
                current = directory
                FileVisitResult.CONTINUE
            }

            onPostVisitDirectory { _, _ ->
                current = current.parent
                val top = stack.peek()
                if (!top.hasChildNodes()) {
                    top.parentNode.removeChild(top)
                }
                stack.pop()
                FileVisitResult.CONTINUE
            }

            onVisitFile { file, _ ->
                val relative = current.relativize(file)
                val relativeToDir = dir.relativize(file)
                if (relativeToDir.equals(Paths.get("app", ".jpackage.xml"))) {
                    return@onVisitFile FileVisitResult.CONTINUE
                }
                val indent = "  ".repeat(stack.size - 1)
                logger.debug("${indent}file: $relative")
                val id = "File${fileId++}"
                val uuid = UUID.randomUUID()
                val fileId = fileIdFromUUID(uuid)
                list.add(FileEntry(id, relativeToDir, fileId))
                val component = stack.peek().createChild("Component", id) {
                    setAttribute("Guid", "{$uuid}")
                }
                component.createChild("File", fileId) {
                    setAttribute("KeyPath", "yes")
                    setAttribute("Source", file.toString())
                }
                FileVisitResult.CONTINUE
            }
        }
        return list
    }

    private fun fileIdFromUUID(uuid: UUID): String {
        return "file" + uuid.toString().replace("-", "")
    }

    data class FileEntry(internal val id: String, internal val file: Path, internal val fileId: String)
}
