package dev.kmpyt

import dev.kmpyt.extraction.*
import kotlinx.coroutines.delay
import kotlin.test.*

class IosYouTubeJavaScriptRuntimeTest {
    @Test fun realWebKitExecutesNestedPlayerClosureWithDomAndUrl() = iosTest {
        val compiled = ContextYouTubePlayerProgramCompiler(IosYouTubeJavaScriptRuntime()).compile("""
            var _yt_player={};(function(realm){
              var hidden=1;(function(){var nested=[1,2];nested.pop();})();
              var node=document.createElement('a');node.href='https://offline.invalid/path';
              if(node.hostname!=='offline.invalid')throw new Error('DOM unavailable');
              if(location.origin!=='https://kmpyt.invalid')throw new Error('Opaque document origin');
              if(document.cookie!=='')throw new Error('Cookie getter or data-store isolation failed');
              realm.Resource=function(input,enabled){
                var parsed=new URL(input);
                this.set=function(key,value){parsed.searchParams.set(key,value);};
                this.get=function(key){var value=parsed.searchParams.get(key);return key==='n'&&value?value.slice(hidden)+value.slice(0,hidden):value;};
              };
              var assemble;
              assemble=function(uri,key,challenge){uri=new realm.Resource(uri,!0);uri.set('alr','yes');challenge&&uri.set(key,challenge.split('').reverse().join(''));return uri;};
              var config={signatureTimestamp:21002};
            })(_yt_player);
        """.trimIndent(), YouTubeProgramBinding())
        val transforms = assertNotNull(compiled.transforms, compiled.diagnostics.joinToString { it.code })
        try {
            assertEquals(21002L, transforms.signatureTimestamp)
            assertEquals("fedcba", transforms.signature("abcdef"))
            assertEquals("bcda", transforms.throttling("abcd"))
        } finally { transforms.close() }
        assertFailsWith<IllegalStateException> { transforms.throttling("abcd") }
    }

    @Test fun stringsAreEscapedAndRealDomIsIsolatedBetweenSessions() = iosTest {
        val runtime = IosYouTubeJavaScriptRuntime()
        val program = """
            if(document.body.childElementCount!==0)throw new Error('leaked DOM');
            document.body.appendChild(document.createElement('div'));
            globalThis.__kmpyt_capabilities={signature:true,throttling:true};
            globalThis.__kmpyt_transform=function(kind,input){return kind+':'+input;};
        """.trimIndent()
        repeat(2) {
            val session = runtime.prepare(program)
            try {
                val input = "quote'\" newline\nعربي\\end"
                assertEquals("signature:$input", session.call(YouTubeJavaScriptFunction.SIGNATURE, input))
            } finally { session.close() }
        }
    }

    @Test fun malformedExportsFailWithBoundedCodes() = iosTest {
        val failure = assertFailsWith<YouTubeJavaScriptFailure> {
            IosYouTubeJavaScriptRuntime().prepare("throw new Error('private source secret');")
        }
        assertEquals("PROGRAM_INITIALIZATION_FAILED", failure.code)
        assertFalse(failure.message.orEmpty().contains("private source secret"))
    }

    @Test fun actualWebKitBlocksExternalFetchAndNavigation() = iosTest {
        val session = IosYouTubeJavaScriptRuntime().prepare("""
            document.title='kmpyt-offline';
            var blockedByPolicy=false;
            document.addEventListener('securitypolicyviolation',function(event){
              if(event.effectiveDirective==='connect-src')blockedByPolicy=true;
            });
            globalThis.__kmpyt_capabilities={signature:true,throttling:true};
            globalThis.__kmpyt_transform=function(kind,input){
              if(kind==='signature'){
                var blocked=0;
                ['https://network.invalid/private','file:///etc/hosts'].forEach(function(url){
                  try { var request=new XMLHttpRequest();request.open('GET',url,false);request.send(); }
                  catch(error){blocked++;}
                });
                window.location.assign('https://navigation.invalid/');
                return input+':blocked='+blocked;
              }
              return input+':'+document.title+':policy='+blockedByPolicy;
            };
        """.trimIndent())
        try {
            assertEquals("probe:blocked=2", session.call(YouTubeJavaScriptFunction.SIGNATURE, "probe"))
            delay(100)
            assertEquals("probe:kmpyt-offline:policy=true", session.call(YouTubeJavaScriptFunction.THROTTLING, "probe"))
        } finally { session.close() }
    }
}
