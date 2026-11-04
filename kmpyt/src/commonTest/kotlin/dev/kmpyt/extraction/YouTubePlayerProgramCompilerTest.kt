package dev.kmpyt.extraction

import kotlin.test.*

class YouTubePlayerProgramCompilerTest {
    private val compiler = BoundedYouTubePlayerProgramCompiler()
    private fun signature(body: String): YouTubeUrlTransforms = assertNotNull(compiler.compile(body, YouTubeProgramBinding(signatureFunction = "sig")).transforms)

    @Test fun executesCompleteArrayProgramWithHelperOperationsAndActualTimestamp() {
        val source = """
            var helpers={swap:function(a,b){var saved=a[0];a[0]=a[b%a.length];a[b%a.length]=saved;},
                reverse:function(a){a.reverse();},drop:function(a,b){a.splice(0,b);}};
            function sig(input){input=input.split("");helpers.swap(input,2);helpers.reverse(input,99);helpers.drop(input,1);return input.join("");}
            var configuration={signatureTimestamp:20345};
        """.trimIndent()
        val transform = signature(source)
        assertEquals(20345L, transform.signatureTimestamp)
        assertEquals("gfedabc", transform.signature("abcdefgh"))
        assertTrue(transform.signatureAvailable)
        assertFalse(transform.throttlingAvailable)
    }

    @Test fun discoversOnlyBoundCallSitesAndExecutesDifferentNProgram() {
        val source = """
            function unrelated(a){a=a.split("");a.reverse();return a.join("");}
            function sig(a){a=a.split("");a=a.slice(2,-1);return a.join("");}
            throttle=function(a){var units=a.split("");units.push(units.shift());return units.join("");};
            params.set("signature",sig(value));params.set("n",throttle(value));
        """.trimIndent()
        val transforms = assertNotNull(compiler.compile(source, YouTubeProgramBinding()).transforms)
        assertEquals("cdefg", transforms.signature("abcdefgh"))
        assertEquals("bcdefgha", transforms.throttling("abcdefgh"))
        assertTrue(transforms.throttlingAvailable)
    }

    @Test fun followsUtf16CodeUnitSemanticsAndDoesNotReuseMutableInputs() {
        val transform = signature("""function sig(a){a=a.split("");a.reverse();return a.join("");}""")
        val input = "A\uD83D\uDE00B"
        assertEquals("B\uDE00\uD83DA", transform.signature(input))
        assertEquals("zyx", transform.signature("xyz"))
        assertEquals("B\uDE00\uD83DA", transform.signature(input))
    }

    @Test fun accuratelyDistinguishesModuloSwapAndSparseArrayAssignment() {
        val prefix = """var h={swap:function(a,b){var temp=a[0];a[0]=a[b%a.length];"""
        val suffix = """}};function sig(a){a=a.split("");h.swap(a,5);return a.join("");}"""
        val modulo = signature(prefix + "a[b%a.length]=temp;" + suffix)
        val sparse = signature(prefix + "a[b]=temp;" + suffix)
        assertEquals("cba", modulo.signature("abc"))
        assertEquals("cbca", sparse.signature("abc"))
    }

    @Test fun commentsAndQuotedHelperKeysDoNotChangeOperationSelection() {
        val transforms = signature("""
            var h={"r":function(x){/* a fake } brace */ return x.reverse();}};
            function sig(a){"use strict"; a=a.split(""); h["r"](a); return a.join("");}
        """.trimIndent())
        assertEquals("fedcba", transforms.signature("abcdef"))
    }

    @Test fun rejectsWholeFunctionWhenAnOperationOrHelperStatementIsUnsupported() {
        val scripts = listOf(
            """function sig(a){a=a.split("");a.reverse();a.push(secret);return a.join("");}""",
            """var h={r:function(a){a.reverse();send(a);}};function sig(a){a=a.split("");h.r(a);return a.join("");}""",
            """function sig(a){a=a.split("");if(a.length>2)a.reverse();return a.join("");}""",
            """function sig(seed,a){a=a.split("");a.reverse();return a.join("");}""",
            """function sig(a){a=a.split("");"a".reverse();return a.join("");}""",
            """function sig(a){const b=a.split("");b=b.slice(1);return b.join("");}""",
            """var h={r:function(a){returnA.reverse();}};function sig(a){a=a.split("");h.r(a);return a.join("");}""",
            """var h={r:function(a){A.reverse();}};function sig(a){a=a.split("");h.r(a);return a.join("");}""",
            """var h={r:function(a){a.reverse();}.bind(other)};function sig(a){a=a.split("");h.r(a);return a.join("");}""",
            """var h={r:function(a){a.reverse();}};h.r=other;function sig(a){a=a.split("");h.r(a);return a.join("");}""",
            """function sig(a){a=a.split("");a.reverse();return a.join("");}sig=other;""",
        )
        scripts.forEach { script ->
            val result = compiler.compile(script, YouTubeProgramBinding(signatureFunction = "sig"))
            assertNull(result.transforms)
            assertTrue(result.diagnostics.any { it.code == "SIGNATURE_PROGRAM_UNSUPPORTED" })
        }
    }

    @Test fun rejectsAmbiguousBindingsAndHelperDefinitions() {
        val a = """function one(a){a=a.split("");a.reverse();return a.join("");}function two(a){a=a.split("");a.reverse();return a.join("");}p.set("signature",one(v));p.set("signature",two(v));"""
        assertNull(compiler.compile(a, YouTubeProgramBinding()).transforms)
        val duplicated = """var h={r:function(a){a.reverse();}};var h={r:function(a){a.splice(0,1);}};function sig(a){a=a.split("");h.r(a);return a.join("");}"""
        assertNull(compiler.compile(duplicated, YouTubeProgramBinding(signatureFunction = "sig")).transforms)
    }

    @Test fun rejectsHelperBindingsShadowedByAnInputOrLocalArray() {
        val helper = """var h={r:function(a){a.reverse();}};"""
        val scripts = listOf(
            helper + """function sig(h){var a=h.split("");h.r(a);return a.join("");}""",
            helper + """function sig(input){var h=input.split("");h.r(h);return h.join("");}""",
            helper + """function sig(input){a=input.split("");h.r(a);return a.join("");}""",
        )
        scripts.forEach { script ->
            val result = compiler.compile(script, YouTubeProgramBinding(signatureFunction = "sig"))
            assertNull(result.transforms)
            assertTrue(result.diagnostics.any { it.code == "SIGNATURE_PROGRAM_UNSUPPORTED" })
        }
    }

    @Test fun rejectsDefinitionsWhoseLexicalVisibilityCannotBeProved() {
        val signature = """function sig(a){a=a.split("");h.r(a);return a.join("");}"""
        val scripts = listOf(
            """function unrelated(){var h={r:function(a){a.reverse();}};}""" + signature,
            """if(flag){var h={r:function(a){a.reverse();}};}""" + signature,
            """var object={};object.h={r:function(a){a.reverse();}};""" + signature,
            """function unrelated(){function sig(a){a=a.split("");a.reverse();return a.join("");}}""",
            """var object={};object.sig=function(a){a=a.split("");a.reverse();return a.join("");};""",
            """var another=function sig(a){a=a.split("");a.reverse();return a.join("");};""",
            """var h={r:function(a){a.reverse();}};unknownMutation(h);""" + signature,
            """function sig(a){a=a.split("");a.reverse();return a.join("");}function unrelated(){function sig(a){return secret;}params.set("signature",sig(value));}""",
        )
        scripts.forEach { script ->
            val result = compiler.compile(script, YouTubeProgramBinding(signatureFunction = "sig"))
            assertNull(result.transforms)
            assertTrue(result.diagnostics.any { it.code == "SIGNATURE_PROGRAM_UNSUPPORTED" })
        }
    }

    @Test fun keepsExplicitTopLevelLocalArrayAndHelperProgramSupported() {
        val source = """var h={r:function(a){a.reverse();}};var sig=function(input){let a=input.split("");h.r(a);return a.join("");};"""
        val transforms = signature(source)
        assertTrue(transforms.signatureAvailable)
        assertEquals("fedcba", transforms.signature("abcdef"))
    }

    @Test fun decodesRealJsHelperKeyEscapesWithoutInventingAnIdentifierMatch() {
        val wrongKeys = listOf("b" to "\\b", "f" to "\\f", "v" to "\\v", "0" to "\\0")
        wrongKeys.forEach { (identifier, escape) ->
            val script = "var h={\"$escape\":function(a){a.reverse();}};function sig(a){a=a.split(\"\");h[\"$identifier\"](a);return a.join(\"\");}"
            assertNull(compiler.compile(script, YouTubeProgramBinding(signatureFunction = "sig")).transforms)
        }
        val exactKey = """var h={"\b":function(a){a.reverse();}};function sig(a){a=a.split("");h["\b"](a);return a.join("");}"""
        assertEquals("cba", signature(exactKey).signature("abc"))
        val legacy = """var h={"\1":function(a){a.reverse();}};function sig(a){a=a.split("");h["1"](a);return a.join("");}"""
        val result = compiler.compile(legacy, YouTubeProgramBinding(signatureFunction = "sig"))
        assertNull(result.transforms)
        assertTrue(result.diagnostics.any { it.code == "PLAYER_PROGRAM_LEXING_UNSUPPORTED" })
    }

    @Test fun doesNotInferFunctionsFromStringLiteralsOrUnboundSplitJoinFunctions() {
        val unbound = """function unknown(a){a=a.split("");a.reverse();return a.join("");}"""
        assertNull(compiler.compile(unbound, YouTubeProgramBinding()).transforms)
        val quoted = """"function" sig(a){a=a.split("");a.reverse();return a.join("");}"""
        assertNull(compiler.compile(quoted, YouTubeProgramBinding(signatureFunction = "sig")).transforms)
    }

    @Test fun enforcesInputProgramAndTokenBudgetsWithoutPartialCompilation() {
        val transform = signature("""function sig(a){a=a.split("");a.reverse();return a.join("");}""")
        assertFailsWith<IllegalArgumentException> { transform.signature("") }
        assertFailsWith<IllegalArgumentException> { transform.signature("a".repeat(8193)) }
        val excessive = compiler.compile(" ".repeat(BoundedYouTubePlayerProgramCompiler.MAX_SOURCE + 1), YouTubeProgramBinding())
        assertNull(excessive.transforms)
        assertTrue(excessive.diagnostics.any { it.code == "PLAYER_PROGRAM_SOURCE_LIMIT" })
        val tokenHeavy = compiler.compile("x;".repeat(100_001), YouTubeProgramBinding())
        assertNull(tokenHeavy.transforms)
        assertTrue(tokenHeavy.diagnostics.any { it.code == "PLAYER_PROGRAM_TOKEN_LIMIT" })
    }

    @Test fun separatesRoleAvailabilityAndRejectsEmptyOutput() {
        val source = """function sig(a){a=a.split("");a.splice(0,8192);return a.join("");}function throttle(a){a=a.split("");unknown(a);return a.join("");}"""
        val result = compiler.compile(source, YouTubeProgramBinding("sig", "throttle"))
        val transform = assertNotNull(result.transforms)
        assertTrue(transform.signatureAvailable)
        assertFalse(transform.throttlingAvailable)
        assertFailsWith<IllegalArgumentException> { transform.signature("abc") }
    }

    @Test fun updatesOnlyTheNamedQueryFieldAndPreservesSignedBytes() {
        val url = "https://capture.example/media.mp4?token=a%2Fb%2bc&n=old&empty=&other=x+y#fragment"
        assertEquals("https://capture.example/media.mp4?token=a%2Fb%2bc&n=next%2Fvalue&empty=&other=x+y#fragment",
            YouTubeUrlAssembly.replaceParameter(url, "n", "next/value"))
        assertEquals("https://capture.example/media.mp4?sig=abc", YouTubeUrlAssembly.replaceParameter("https://capture.example/media.mp4", "sig", "abc"))
        assertFailsWith<IllegalArgumentException> { YouTubeUrlAssembly.replaceParameter("https://capture.example/?n=a&n=b", "n", "c") }
        assertFailsWith<IllegalArgumentException> { YouTubeUrlAssembly.replaceParameter(url, "expire", "123") }
        assertFailsWith<IllegalArgumentException> { YouTubeUrlAssembly.replaceParameter(url, "n&token", "x") }
    }
}
