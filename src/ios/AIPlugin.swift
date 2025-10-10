//
//  AIPlugin.swift
//  Cordova AI Demo – iOS plugin
//

import Foundation

@objc(AIPlugin) class AIPlugin: CDVPlugin {

    @objc(ask:)
    func ask(command: CDVInvokedUrlCommand) {
        let question = (command.arguments.first as? String) ?? "Kérdés hiányzik."

        Task { @MainActor in
            let answer = await self.answerUsingFMOrFallback(question: question)
            let result = CDVPluginResult(status: .ok, messageAs: answer)
            self.commandDelegate?.send(result, callbackId: command.callbackId)
        }
    }

    // MARK: - FM (ha van) vagy fallback
    private func answerUsingFMOrFallback(question: String) async -> String {
        #if canImport(FoundationModels)
        if #available(iOS 18.0, *) {
            do {
                return try await FMFacade.shared.generateText(for: question)
            } catch {
                return "FM hiba: \(error.localizedDescription)"
            }
        } else {
            return "A Foundation Models iOS 18 alatt érhető el."
        }
        #else
        // Publikus SDK eset: nincs FoundationModels modul → fix/fallback
        return "Holnap 10:00, A/5 203-as terem. (fallback)"
        #endif
    }
}

#if canImport(FoundationModels)
import FoundationModels

/// Egy nagyon vékony "facade", a neten talált SwiftFM mintájára.
/// - Figyelem: ez **privát** API-kra épül; publikus Xcode SDK-val várhatóan nem fordul.
@available(iOS 18.0, *)
actor FMFacade {
    static let shared = FMFacade()

    // A példa alapján:
    // - LanguageModelSession kezeli a konverzációt
    // - GenerationOptions paraméterez
    private let session: LanguageModelSession

    // Alap beállítások (system prompt, temperature, max token)
    struct Config {
        var system: String? = nil
        var temperature: Double = 0.6
        var maximumResponseTokens: Int? = nil
    }
    private let config: Config

    init(config: Config = .init()) {
        self.config = config
        if let sys = config.system {
            self.session = LanguageModelSession(instructions: sys)
        } else {
            self.session = LanguageModelSession()
        }
    }

    func generateText(for prompt: String) async throws -> String {
        var opts = GenerationOptions()
        opts.temperature = config.temperature
        if let max = config.maximumResponseTokens {
            opts.maximumResponseTokens = max
        }
        let r = try await session.respond(to: prompt, options: opts)
        return r.content
    }

    // (opcionális) streaming minta – ha kell
    func streamText(for prompt: String) -> AsyncThrowingStream<String, Error> {
        let s = self.session
        let cfg = self.config
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var opts = GenerationOptions()
                    opts.temperature = cfg.temperature
                    if let max = cfg.maximumResponseTokens {
                        opts.maximumResponseTokens = max
                    }
                    let p = Prompt(prompt)
                    let stream = s.streamResponse(options: opts, prompt: { @Sendable in p })
                    for try await snap in stream {
                        if Task.isCancelled { break }
                        continuation.yield(snap.content)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // (opcionális) elérhetőség lekérdezése
    static var isModelAvailable: Bool {
        SystemLanguageModel.default.isAvailable
    }
}
#endif
