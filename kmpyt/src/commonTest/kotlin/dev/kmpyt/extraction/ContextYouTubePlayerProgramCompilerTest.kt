package dev.kmpyt.extraction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Structural/export and boundary tests; native tests separately execute the real generated JavaScript. */
class ContextYouTubePlayerProgramCompilerTest {
    @Test fun preservesPrivateClosureDependenciesAndDiscoversRenamedUrlHelper() {
        val runtime = RecordingRuntime()
        val source = program()
        val compilation = ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding())
        val transforms = assertNotNull(compilation.transforms)
        assertEquals(21002L, transforms.signatureTimestamp)
        assertTrue(transforms.signatureAvailable)
        assertTrue(transforms.throttlingAvailable)
        assertEquals("cba", transforms.signature("abc"))
        assertEquals("bcda", transforms.throttling("abcd"))
        assertEquals(listOf(YouTubeJavaScriptFunction.SIGNATURE to "abc", YouTubeJavaScriptFunction.THROTTLING to "abcd"), runtime.calls)
        val emitted = assertNotNull(runtime.program)
        val exportStart = emitted.indexOf(";globalThis.__kmpyt_capabilities")
        assertTrue(exportStart > 0)
        assertEquals(source.substringBeforeLast("})(_yt_player);"), emitted.substring(0, exportStart))
        assertTrue(emitted.endsWith("})(_yt_player);"))
        assertTrue(emitted.contains("var privateRotation=3;"))
        assertTrue(emitted.contains("assemble(neutral,\"signature\",globalThis.encodeURIComponent(input)).get(\"signature\")"))
        assertTrue(emitted.contains("if(typeof result===\"string\")result=globalThis.decodeURIComponent(result)"))
        assertTrue(emitted.contains("new realm.Resource(neutral+\"?n=\"+globalThis.encodeURIComponent(input),true)"))
        assertTrue(compilation.diagnostics.any { it.code == "PLAYER_CONTEXT_RUNTIME_PREPARED" })
        assertFalse(compilation.diagnostics.any { it.message.contains("runtime-secret") })
    }

    @Test fun namesAreDiscoveredFromStructureRatherThanCurrentObfuscatedIdentifiers() {
        val runtime = RecordingRuntime()
        val source = program().replace("realm", "namespace").replace("Resource", "UrlBox").replace("assemble", "makeResource")
        val result = ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding())
        assertNotNull(result.transforms)
        assertTrue(assertNotNull(runtime.program).contains("makeResource(neutral"))
        assertTrue(assertNotNull(runtime.program).contains("new namespace.UrlBox(neutral"))
    }

    @Test fun recognizesEndOfFunctionAutomaticSemicolonInsertionButRejectsReturnLineBreaks() {
        val runtime = RecordingRuntime()
        val minified = program().replace("return uri;}", "return uri}")
        assertNotNull(ContextYouTubePlayerProgramCompiler(runtime).compile(minified, YouTubeProgramBinding()).transforms)
        listOf("return\nuri", "return/*\n*/uri", "return\u2028uri", "return\u2029uri").forEach { invalidReturn ->
            val rejectedRuntime = RecordingRuntime()
            val source = program().replace("return uri;", "$invalidReturn;")
            assertNull(ContextYouTubePlayerProgramCompiler(rejectedRuntime).compile(source, YouTubeProgramBinding()).transforms)
            assertNull(rejectedRuntime.program)
        }
    }

    @Test fun stringsCommentsAndRegexBodiesDoNotCreateFunctionBindings() {
        val source = program().replace("var privateRotation=3;", """
            var privateRotation=3;
            var text="pretend=function(a,b,c){a=new realm.Other(a,!0);a.set('alr','yes');return a;};";
            /* duplicate=function(a,b,c){a=new realm.Other(a,!0);a.set('alr','yes');return a;}; */
            var expression=/[{}]/;
        """.trimIndent())
        val runtime = RecordingRuntime()
        assertNotNull(ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding()).transforms)
        assertFalse(assertNotNull(runtime.program).contains("typeof pretend"))
    }

    @Test fun rejectsAmbiguousOrReassignedFunctionAndConstructorBindings() {
        val duplicate = helper("second")
        val sources = listOf(
            program(extra = duplicate),
            program(extra = "assemble=other;"),
            program(extra = "assemble+=1;"),
            program(extra = "realm.Resource=function(a,b){return other;};"),
            program(extra = "realm.Resource=other;"),
            program(extra = "realm=other;"),
        )
        sources.forEach { source ->
            val runtime = RecordingRuntime()
            val result = ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding())
            assertNull(result.transforms)
            assertNull(runtime.program)
            assertTrue(result.diagnostics.any { it.code == "PLAYER_CONTEXT_STRUCTURE_UNSUPPORTED" })
        }
    }

    @Test fun nestedConditionalAndShadowedDefinitionsCannotBecomeClosureExports() {
        val actual = helper("assemble")
        val sources = listOf(
            program().replace(actual, "function unrelated(){${actual}}"),
            program().replace(actual, "if(condition){${actual}}"),
            program().replace(actual, "if(condition)$actual"),
            program().replace("function(uri,key,challenge)", "function(realm,key,challenge)").replace("uri=new realm.Resource", "realm=new realm.Resource"),
            program().replace("uri=new realm.Resource", "var realm=other;uri=new realm.Resource"),
            program().replace("realm.Resource=function(input,enabled)", "unrelated.Resource=function(input,enabled)"),
        )
        sources.forEach { source ->
            val runtime = RecordingRuntime()
            assertNull(ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding()).transforms)
            assertNull(runtime.program)
        }
    }

    @Test fun unsupportedWrapperAndInterpolatedTemplatesAreNotPartiallyExecuted() {
        val sources = listOf(
            program() + "var later=1;",
            program() + program(),
            program().replace("})(_yt_player);", "})(other_namespace);"),
            program().replace("var privateRotation=3;", "var privateRotation=`\${dynamic}`;"),
            program().replace("var _yt_player={};", "\"var\" _yt_player={};"),
            program().replace("aMissingToken", "other").dropLast(2),
        )
        sources.forEach { source ->
            val runtime = RecordingRuntime()
            assertNull(ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding()).transforms)
            assertNull(runtime.program)
        }
    }

    @Test fun fullContextCannotSilentlyOverrideExplicitDifferentFunctionBindings() {
        val runtime = RecordingRuntime()
        val result = ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding(signatureFunction = "other"))
        assertNull(result.transforms)
        assertNull(runtime.program)
        assertTrue(result.diagnostics.any { it.code == "PLAYER_CONTEXT_EXPLICIT_BINDING_UNSUPPORTED" })
    }

    @Test fun completeClassicProgramUsesSharedKotlinWithoutStartingNativeRuntime() {
        val source = """function s(a){a=a.split("");a.reverse();return a.join("");}
            function n(a){a=a.split("");a.push(a.shift());return a.join("");}p.set("signature",s(v));p.set("n",n(v));"""
        val runtime = RecordingRuntime()
        val transforms = assertNotNull(ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding()).transforms)
        assertEquals("cba", transforms.signature("abc"))
        assertEquals("bca", transforms.throttling("abc"))
        assertNull(runtime.program)
    }

    @Test fun runtimeInitializationAndCallErrorsNeverExposeRawJavascriptOrInputValues() {
        val runtime = RecordingRuntime()
        runtime.prepareFailure = IllegalStateException("runtime-secret cookie=value")
        val failed = ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding())
        assertNull(failed.transforms)
        assertTrue(failed.diagnostics.any { it.code == "PLAYER_CONTEXT_RUNTIME_UNAVAILABLE" })
        assertFalse(failed.diagnostics.any { it.message.contains("runtime-secret") || it.message.contains("cookie=value") })
        runtime.prepareFailure = null
        val transforms = assertNotNull(ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding()).transforms)
        runtime.callFailure = IllegalStateException("runtime-secret input=private")
        val error = assertFailsWith<IllegalStateException> { transforms.signature("private") }
        assertFalse(assertNotNull(error.message).contains("private"))
        assertFalse(assertNotNull(error.message).contains("runtime-secret"))
    }

    @Test fun unchangedEmptyOversizedAndPlayerErrorSentinelsAreFailures() {
        val runtime = RecordingRuntime()
        val transforms = assertNotNull(ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding()).transforms)
        listOf("abc", "", "enhanced_except_opaque", "undefined", "null", "x".repeat(8193)).forEach { answer ->
            runtime.signatureAnswer = answer
            assertFailsWith<IllegalStateException> { transforms.signature("abc") }
        }
        assertFailsWith<IllegalArgumentException> { transforms.signature("") }
        assertFailsWith<IllegalArgumentException> { transforms.signature("x".repeat(8193)) }
    }

    @Test fun cancellationPropagatesAndUnavailableSessionsAreClosed() {
        val runtime = RecordingRuntime()
        runtime.capabilities = YouTubeJavaScriptCapabilities(false, false)
        assertNull(ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding()).transforms)
        assertTrue(runtime.closed)
        runtime.capabilities = YouTubeJavaScriptCapabilities(true, true)
        val transforms = assertNotNull(ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding()).transforms)
        runtime.callFailure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { transforms.signature("abc") }
        runtime.prepareFailure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding()) }
    }

    @Test fun sourceLimitAndAmbiguousTimestampAreReportedWithoutGuessing() {
        val runtime = RecordingRuntime()
        val compiler = ContextYouTubePlayerProgramCompiler(runtime)
        val large = compiler.compile("x".repeat(ContextYouTubePlayerProgramCompiler.MAX_SOURCE + 1), YouTubeProgramBinding())
        assertNull(large.transforms)
        assertEquals("PLAYER_CONTEXT_SOURCE_LIMIT", large.diagnostics.single().code)
        assertNull(runtime.program)
        val transforms = assertNotNull(compiler.compile(program(extra = "var different={signatureTimestamp:21003};"), YouTubeProgramBinding()).transforms)
        assertNull(transforms.signatureTimestamp)
    }

    @Test fun closingCompilationDisposesSessionOnceAndRejectsFurtherNativeCalls() {
        val runtime = RecordingRuntime()
        val transforms = assertNotNull(ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding()).transforms)
        transforms.close()
        transforms.close()
        assertEquals(1, runtime.closeCount)
        assertFailsWith<IllegalStateException> { transforms.throttling("abcd") }
    }

    @Test fun loaderEvictionAndClearDisposeTheirActualCachedSessionObjects() = runTest {
        val runtime = RecordingRuntime()
        val loader = YouTubePlayerProgramLoader(ResearchTransport { request ->
            ResearchResponse(request.url, 200, program(), mapOf("content-type" to "application/javascript"))
        }, ContextYouTubePlayerProgramCompiler(runtime), cacheEntries = 1)
        val context = ExtractionContext("https://capture.example/youtube/owned", "https://capture.example/youtube/owned")
        assertNotNull(loader.load(context, "<script src='/first.js'></script>").compilation?.transforms)
        assertEquals(0, runtime.closeCount)
        assertNotNull(loader.load(context, "<script src='/second.js'></script>").compilation?.transforms)
        assertEquals(1, runtime.closeCount)
        loader.clear()
        assertEquals(2, runtime.closeCount)
        loader.close()
        assertEquals(2, runtime.closeCount)
    }

    /** Independently authored test program; its native execution belongs to integration tests. */
    private fun program(extra: String = "") = """
        var _yt_player={};(function(realm){
        var privateRotation=3;
        realm.Resource=function(input,enabled){
          var parsed=new URL(input);
          this.set=function(key,value){parsed.searchParams.set(key,value);};
          this.get=function(key){var value=parsed.searchParams.get(key);return key==="n"&&value?value.slice(privateRotation)+value.slice(0,privateRotation):value;};
        };
        var assemble;
        ${helper("assemble")}
        var configuration={signatureTimestamp:21002};
        $extra
        })(_yt_player);
    """.trimIndent()

    private fun helper(name: String) = """$name=function(uri,key,challenge){uri=new realm.Resource(uri,!0);uri.set("alr","yes");challenge&&uri.set(key,challenge.split("").reverse().join(""));return uri;};"""

    private class RecordingRuntime : YouTubeJavaScriptRuntime {
        var program: String? = null
        var capabilities = YouTubeJavaScriptCapabilities(true, true)
        var prepareFailure: Exception? = null
        var callFailure: Exception? = null
        var signatureAnswer = "cba"
        var closed = false
        var closeCount = 0
        val calls = mutableListOf<Pair<YouTubeJavaScriptFunction, String>>()
        override fun prepare(program: String): YouTubeJavaScriptSession {
            prepareFailure?.let { throw it }
            this.program = program
            return object : YouTubeJavaScriptSession {
                override val capabilities get() = this@RecordingRuntime.capabilities
                override fun call(function: YouTubeJavaScriptFunction, input: String): String {
                    calls += function to input
                    callFailure?.let { throw it }
                    return if (function == YouTubeJavaScriptFunction.SIGNATURE) signatureAnswer else "bcda"
                }
                override fun close() { closed = true; closeCount++ }
            }
        }
    }
}
