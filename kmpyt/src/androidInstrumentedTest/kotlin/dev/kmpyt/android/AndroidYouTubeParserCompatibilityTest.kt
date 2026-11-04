package dev.kmpyt.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kmpyt.extraction.ContextYouTubePlayerProgramCompiler
import dev.kmpyt.extraction.YouTubeJavaScriptRuntime
import dev.kmpyt.extraction.YouTubeJavaScriptSession
import dev.kmpyt.extraction.YouTubeProgramBinding
import dev.kmpyt.extraction.YouTubeUrlAssembly
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on every supported Android API, including API 29 where List.removeLast is unavailable. */
@RunWith(AndroidJUnit4::class)
class AndroidYouTubeParserCompatibilityTest {
    @Test fun nestedPlayerClosureCompilesAndBothTransformsExecuteInNativeRuntime() = runBlocking {
        withContext(Dispatchers.IO) {
            val runtime = AndroidYouTubeJavaScriptRuntime(ApplicationProvider.getApplicationContext<Context>())
            val compilation = ContextYouTubePlayerProgramCompiler(runtime).compile(program(), YouTubeProgramBinding())
            val transforms = compilation.transforms
                ?: throw AssertionError("Authored closure was rejected: ${compilation.diagnostics.map { it.code }}")
            try {
                // Require the context parser and actual WebView runtime, not a bounded fallback.
                assertTrue(compilation.diagnostics.any { it.code == "PLAYER_CONTEXT_RUNTIME_PREPARED" })
                assertEquals(21002L, transforms.signatureTimestamp)
                assertTrue(transforms.signatureAvailable)
                assertTrue(transforms.throttlingAvailable)
                assertEquals("fedcba", transforms.signature("abcdef"))
                assertEquals("defabc", transforms.throttling("abcdef"))
            } finally { transforms.close() }
        }
    }

    @Test fun unbalancedNestedClosureReturnsDiagnosticWithoutExecutingSource() = runBlocking {
        withContext(Dispatchers.IO) {
            val nativeRuntime = AndroidYouTubeJavaScriptRuntime(ApplicationProvider.getApplicationContext<Context>())
            var preparations = 0
            val runtime = object : YouTubeJavaScriptRuntime {
                override fun prepare(program: String): YouTubeJavaScriptSession {
                    preparations++
                    return nativeRuntime.prepare(program)
                }
            }
            val source = program().replace("var privateRotation=3;", "var privateRotation=3;if(true){")
            val compilation = ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding())
            try {
                assertNull(compilation.transforms)
                assertTrue(compilation.diagnostics.any { it.code == "PLAYER_CONTEXT_STRUCTURE_UNSUPPORTED" })
                assertEquals(0, preparations)
            } finally { compilation.transforms?.close() }
        }
    }

    @Test fun encodedSignatureHelperReturnsRawSignatureBeforeFinalUrlAssembly() = runBlocking {
        withContext(Dispatchers.IO) {
            val runtime = AndroidYouTubeJavaScriptRuntime(ApplicationProvider.getApplicationContext<Context>())
            val source = program().replace(
                "challenge.split(\"\").reverse().join(\"\")",
                "encodeURIComponent(decodeURIComponent(challenge).split(\"\").reverse().join(\"\"))",
            )
            val compilation = ContextYouTubePlayerProgramCompiler(runtime).compile(source, YouTubeProgramBinding())
            val transforms = compilation.transforms
                ?: throw AssertionError("Authored encoded helper was rejected: ${compilation.diagnostics.map { it.code }}")
            try {
                assertTrue(compilation.diagnostics.any { it.code == "PLAYER_CONTEXT_RUNTIME_PREPARED" })
                assertEquals("fedcba", transforms.signature("abcdef"))
                // The literal percent, plus, slash, and equals expose accidental double encoding.
                val signature = transforms.signature("a+/=%25")
                assertEquals("52%=/+a", signature)
                val original = "https://media.invalid/videoplayback?keep=%2f%2B+&signed=A%20B&sig=old&tail=%3d"
                val resource = YouTubeUrlAssembly.replaceParameter(original, "sig", signature)
                assertEquals("52%=/+a", Url(resource).parameters["sig"])
                assertTrue(resource.startsWith("https://media.invalid/videoplayback?keep=%2f%2B+&signed=A%20B&sig="))
                assertTrue(resource.endsWith("&tail=%3d"))
                assertFalse(resource.contains("sig=52%2525"))
            } finally { transforms.close() }
        }
    }

    private fun program() = """
        var _yt_player={};(function(realm){
        var privateRotation=3;
        realm.Resource=function(input,enabled){
          var parsed=new URL(input);
          this.set=function(key,value){parsed.searchParams.set(key,value);};
          this.get=function(key){var value=parsed.searchParams.get(key);return key==="n"&&value?value.slice(privateRotation)+value.slice(0,privateRotation):value;};
        };
        var assemble;
        assemble=function(uri,key,challenge){uri=new realm.Resource(uri,!0);uri.set("alr","yes");challenge&&uri.set(key,challenge.split("").reverse().join(""));return uri;};
        var configuration={signatureTimestamp:21002};
        })(_yt_player);
    """.trimIndent()
}
