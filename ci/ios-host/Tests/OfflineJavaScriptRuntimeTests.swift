import XCTest
import KmpYT

/// These tests require the UIKit host: a bare Kotlin/Native executable cannot host WebKit's process lifecycle.
final class OfflineJavaScriptRuntimeTests: XCTestCase {
    private func worker<T>(_ operation: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do { continuation.resume(returning: try operation()) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }

    func testActualPlayerClosureUsesDomUrlOriginAndNativeCookieGetter() async throws {
        try await worker {
            let runtime = try IosYouTubeJavaScriptRuntime(timeoutSeconds: 20)
            let compiler = ContextYouTubePlayerProgramCompiler(runtime: runtime, boundedCompiler: BoundedYouTubePlayerProgramCompiler())
            let source = """
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
                """
            let result = compiler.compile(source: source, binding: YouTubeProgramBinding(signatureFunction: nil, throttlingFunction: nil))
            let transforms = try XCTUnwrap(result.transforms, result.diagnostics.map { $0.code }.joined(separator: ","))
            defer { try? transforms.close() }
            XCTAssertEqual(transforms.signatureTimestamp?.int64Value, 21002)
            XCTAssertEqual(try transforms.signature(input: "abcdef"), "fedcba")
            XCTAssertEqual(try transforms.throttling(input: "abcd"), "bcda")
        }
    }

    func testEscapedUnicodeAndIndependentNonpersistentSessions() async throws {
        try await worker {
            let runtime = try IosYouTubeJavaScriptRuntime(timeoutSeconds: 20)
            let program = """
                if(document.body.childElementCount!==0)throw new Error('leaked DOM');
                document.body.appendChild(document.createElement('div'));
                globalThis.__kmpyt_capabilities={signature:true,throttling:true};
                globalThis.__kmpyt_transform=function(kind,input){return kind+':'+input;};
                """
            for _ in 0..<2 {
                let session = try runtime.prepare(program: program)
                defer { try? session.close() }
                let input = "quote'\" newline\nعربي\\end"
                XCTAssertEqual(try session.call(function: .signature, input: input), "signature:" + input)
                try session.close()
                XCTAssertThrowsError(try session.call(function: .signature, input: "closed"))
            }
        }
    }

    func testMalformedProgramReturnsBoundedNSError() async throws {
        try await worker {
            let runtime = try IosYouTubeJavaScriptRuntime(timeoutSeconds: 20)
            XCTAssertThrowsError(try runtime.prepare(program: "throw new Error('private source secret');")) { error in
                XCTAssertTrue(error.localizedDescription.contains("PROGRAM_INITIALIZATION_FAILED"), error.localizedDescription)
                XCTAssertFalse(error.localizedDescription.contains("private source secret"))
            }
        }
    }

    func testPeerNetworkingCannotBypassOfflinePolicy() async throws {
        try await worker {
            let session = try IosYouTubeJavaScriptRuntime(timeoutSeconds: 20).prepare(program: """
                var blocked=true;
                ['RTCPeerConnection','webkitRTCPeerConnection'].forEach(function(name){
                  if(typeof window[name]==='function'){
                    try { new window[name]({iceServers:[]}); blocked=false; } catch(error) {}
                    var descriptor=Object.getOwnPropertyDescriptor(window,name);
                    if(!descriptor||descriptor.writable||descriptor.configurable)blocked=false;
                  }
                });
                globalThis.__kmpyt_capabilities={signature:true,throttling:false};
                globalThis.__kmpyt_transform=function(kind,input){return input+':peerBlocked='+blocked;};
                """)
            defer { try? session.close() }
            XCTAssertEqual(try session.call(function: .signature, input: "probe"), "probe:peerBlocked=true")
        }
    }

    func testActualWebKitReportsPolicyDenialAndCancelsExternalNavigation() async throws {
        let session = try await worker {
            try IosYouTubeJavaScriptRuntime(timeoutSeconds: 20).prepare(program: """
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
                """)
        }
        defer { try? session.close() }
        let first = try await worker { try session.call(function: .signature, input: "probe") }
        XCTAssertEqual(first, "probe:blocked=2")
        // A policy-violation event proves a CSP denial; an ordinary DNS failure cannot satisfy this assertion.
        try await Task.sleep(nanoseconds: 100_000_000)
        let second = try await worker { try session.call(function: .throttling, input: "probe") }
        XCTAssertEqual(second, "probe:kmpyt-offline:policy=true")
    }
}
