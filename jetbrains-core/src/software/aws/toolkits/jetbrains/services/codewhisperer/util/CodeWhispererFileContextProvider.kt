// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.util

import com.intellij.ide.actions.CopyContentRootPathProvider
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.gist.GistManager
import com.intellij.util.io.DataExternalizer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.annotations.VisibleForTesting
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.core.utils.warn
import software.aws.toolkits.jetbrains.services.codewhisperer.editor.CodeWhispererEditorUtil
import software.aws.toolkits.jetbrains.services.codewhisperer.language.CodeWhispererProgrammingLanguage
import software.aws.toolkits.jetbrains.services.codewhisperer.language.languages.CodeWhispererJava
import software.aws.toolkits.jetbrains.services.codewhisperer.language.languages.CodeWhispererPython
import software.aws.toolkits.jetbrains.services.codewhisperer.language.programmingLanguage
import software.aws.toolkits.jetbrains.services.codewhisperer.model.Chunk
import software.aws.toolkits.jetbrains.services.codewhisperer.model.FileContextInfo
import software.aws.toolkits.jetbrains.services.codewhisperer.model.SupplementalContextInfo
import java.io.DataInput
import java.io.DataOutput
import java.util.Collections

private val contentRootPathProvider = CopyContentRootPathProvider()

private val codewhispererCodeChunksIndex = GistManager.getInstance()
    .newPsiFileGist("psi to code chunk index", 0, CodeWhispererCodeChunkExternalizer) { psiFile ->
        runBlocking {
            val fileCrawler = getFileCrawlerForLanguage(psiFile.programmingLanguage())
            val fileProducers = listOf<suspend (PsiFile) -> List<VirtualFile>>(
                { psiFile -> fileCrawler.listFilesImported(psiFile) },
                { psiFile -> fileCrawler.listFilesWithinSamePackage(psiFile) }
            )
            FileContextProvider.getInstance(psiFile.project).extractCodeChunksFromFiles(psiFile, fileProducers)
        }
    }

private fun getFileCrawlerForLanguage(programmingLanguage: CodeWhispererProgrammingLanguage) = when (programmingLanguage) {
    is CodeWhispererJava -> JavaCodeWhispererFileCrawler
    is CodeWhispererPython -> PythonCodeWhispererFileCrawler
    else -> NoOpFileCrawler()
}

private object CodeWhispererCodeChunkExternalizer : DataExternalizer<List<Chunk>> {
    override fun save(out: DataOutput, value: List<Chunk>) {
        out.writeInt(value.size)
        value.forEach { chunk ->
            out.writeUTF(chunk.path)
            out.writeUTF(chunk.content)
            out.writeUTF(chunk.nextChunk)
        }
    }

    override fun read(`in`: DataInput): List<Chunk> {
        val result = mutableListOf<Chunk>()
        val size = `in`.readInt()
        repeat(size) {
            result.add(
                Chunk(
                    path = `in`.readUTF(),
                    content = `in`.readUTF(),
                    nextChunk = `in`.readUTF()
                )
            )
        }

        return result
    }
}

/**
 * [extractFileContext] will extract the context from a psi file provided
 * [extractSupplementalFileContext] supplemental means file context extracted from files other than the provided one
 */
interface FileContextProvider {
    fun extractFileContext(editor: Editor, psiFile: PsiFile): FileContextInfo

    suspend fun extractSupplementalFileContext(psiFile: PsiFile, fileContext: FileContextInfo): SupplementalContextInfo?

    suspend fun extractCodeChunksFromFiles(psiFile: PsiFile, fileProducers: List<suspend (PsiFile) -> List<VirtualFile>>): List<Chunk>

    fun isTestFile(psiFile: PsiFile): Boolean

    companion object {
        fun getInstance(project: Project): FileContextProvider = project.service()
    }
}

class DefaultCodeWhispererFileContextProvider(private val project: Project) : FileContextProvider {
    override fun extractFileContext(editor: Editor, psiFile: PsiFile): FileContextInfo = CodeWhispererEditorUtil.getFileContextInfo(editor, psiFile)

    /**
     * codewhisperer extract the supplemental context with 2 different approaches depending on what type of file the target file is.
     * 1. source file -> explore files/classes imported from the target file + files within the same project root
     * 2. test file -> explore "focal file" if applicable, otherwise fall back to most "relevant" file.
     * for focal files, e.g. "MainTest.java" -> "Main.java", "test_main.py" -> "main.py"
     * for the most relevant file -> we extract "keywords" from files opened in editor then get the one with the highest similarity with target file
     */
    override suspend fun extractSupplementalFileContext(psiFile: PsiFile, targetContext: FileContextInfo): SupplementalContextInfo? {
        val startFetchingTimestamp = System.currentTimeMillis()
        val isTst = isTestFile(psiFile)

        val chunks = if (isTst && targetContext.programmingLanguage.isUTGSupported()) {
            extractSupplementalFileContextForTst(psiFile, targetContext)
        } else if (!isTst && targetContext.programmingLanguage.isSupplementalContextSupported()) {
            extractSupplementalFileContextForSrc(psiFile, targetContext)
        } else {
            LOG.debug { "${ if (isTst) "UTG" else "CrossFile" } not supported for ${targetContext.programmingLanguage.languageId}" }
            null
        }

        return chunks?.let {
            if (it.isNotEmpty()) {
                LOG.info { "Successfully fetched supplemental context." }
                it.forEachIndexed { index, chunk ->
                    LOG.info {
                        """
                            | Chunk ${index + 1}:
                            |    content = ${chunk.content}
                            |    path = ${chunk.path}
                        """.trimMargin()
                    }
                }
            } else {
                LOG.warn { "Failed to fetch supplemental context, empty list." }
            }

            SupplementalContextInfo(
                isUtg = isTst,
                contents = it,
                latency = System.currentTimeMillis() - startFetchingTimestamp,
                targetFileName = targetContext.filename
            )
        }
    }

    override suspend fun extractCodeChunksFromFiles(psiFile: PsiFile, fileProducers: List<suspend (PsiFile) -> List<VirtualFile>>): List<Chunk> {
        val parseFilesStart = System.currentTimeMillis()
        val hasUsed = Collections.synchronizedSet(mutableSetOf<VirtualFile>())
        val chunks = mutableListOf<Chunk>()

        for (fileProducer in fileProducers) {
            yield()
            val files = fileProducer(psiFile)
            files.forEach { file ->
                yield()
                if (hasUsed.contains(file)) {
                    return@forEach
                }
                val relativePath = runReadAction { contentRootPathProvider.getPathToElement(project, file, null) ?: file.path }
                chunks.addAll(file.toCodeChunk(relativePath))
                hasUsed.add(file)
                if (chunks.size > CHUNK_SIZE) {
                    LOG.debug { "finish fetching 60 chunks in ${System.currentTimeMillis() - parseFilesStart} ms" }
                    return chunks.take(CHUNK_SIZE)
                }
            }
        }

        LOG.debug { "finish fetching 60 chunks in ${System.currentTimeMillis() - parseFilesStart} ms" }
        return chunks.take(CHUNK_SIZE)
    }

    override fun isTestFile(psiFile: PsiFile) = when (psiFile.programmingLanguage()) {
        is CodeWhispererJava -> TestSourcesFilter.isTestSources(psiFile.virtualFile, project)
        is CodeWhispererPython -> PythonCodeWhispererFileCrawler.testFilenamePattern.matches(psiFile.name)
        else -> true
    }

    @VisibleForTesting
    suspend fun extractSupplementalFileContextForSrc(psiFile: PsiFile, targetContext: FileContextInfo): List<Chunk> {
        if (!targetContext.programmingLanguage.isSupplementalContextSupported()) return emptyList()

        // takeLast(11) will extract 10 lines (exclusing current line) of left context as the query parameter
        val query = targetContext.caretContext.leftFileContext.split("\n").takeLast(11).joinToString("\n")

        // step 1: prepare data
        val first60Chunks: List<Chunk> = try {
            runReadAction { codewhispererCodeChunksIndex.getFileData(psiFile) }
        } catch (e: TimeoutCancellationException) {
            throw e
        }

        yield()

        if (first60Chunks.isEmpty()) {
            LOG.warn {
                "0 chunks was found for supplemental context, fileName=${targetContext.filename}, " +
                    "programmingLanaugage: ${targetContext.programmingLanguage}"
            }
            return emptyList()
        }

        // we need to keep the reference to Chunk object because we will need to get "nextChunk" later after calculation
        val contentToChunk = first60Chunks.associateBy { it.content }

        // BM250 only take list of string as argument
        // step 2: bm25 calculation
        val timeBeforeBm25 = System.currentTimeMillis()
        val top3Chunks: List<BM25Result> = BM250kapi(first60Chunks.map { it.content }).topN(query)
        LOG.info { "Time ellapsed for BM25 algorithm: ${System.currentTimeMillis() - timeBeforeBm25} ms; \nResult: $top3Chunks" }

        yield()

        // we use nextChunk as supplemental context
        return top3Chunks.mapNotNull { bm25Result ->
            contentToChunk[bm25Result.docString]?.let {
                Chunk(content = it.nextChunk, path = it.path, score = bm25Result.score)
            }
        }
    }

    @VisibleForTesting
    fun extractSupplementalFileContextForTst(psiFile: PsiFile, targetContext: FileContextInfo): List<Chunk> {
        if (!targetContext.programmingLanguage.isUTGSupported()) return emptyList()

        val focalFile = getFileCrawlerForLanguage(targetContext.programmingLanguage).findFocalFileForTest(psiFile)

        return focalFile?.let { file ->
            val relativePath = contentRootPathProvider.getPathToElement(project, file, null) ?: file.path
            listOf(
                Chunk(
                    content = UTG_PREFIX + file.content().let { it.substring(0, minOf(it.length, UTG_SEGMENT_SIZE)) },
                    path = relativePath
                )
            )
        }.orEmpty()
    }

    companion object {
        private val LOG = getLogger<DefaultCodeWhispererFileContextProvider>()
        private const val CHUNK_SIZE = 60
        private const val UTG_SEGMENT_SIZE = 10200
        private const val UTG_PREFIX = "UTG\n"
    }
}