import Foundation
import AVFoundation
import Speech

@objc(AIPlugin) class AIPlugin: CDVPlugin {

    @objc(ask:)
    func ask(command: CDVInvokedUrlCommand) {
        let question = (command.arguments.first as? String) ?? "Question is missing."

        Task { @MainActor in
            let answer = await self.answerUsingFMOrFallback(question: question)
            let result = CDVPluginResult(status: .ok, messageAs: answer)
            self.commandDelegate?.send(result, callbackId: command.callbackId)
        }
    }

    @objc(transcript:)
    func transcript(command: CDVInvokedUrlCommand) {
        Task { @MainActor in
            do {
                let text: String
                if #available(iOS 10.0, *) {
                    text = try await self.recordAndTranscribe()
                } else {
                    throw TranscriptError.recognizerUnavailable
                }
                let result = CDVPluginResult(status: .ok, messageAs: text)
                self.commandDelegate?.send(result, callbackId: command.callbackId)
            } catch {
                let result = CDVPluginResult(status: .error, messageAs: error.localizedDescription)
                self.commandDelegate?.send(result, callbackId: command.callbackId)
            }
        }
    }

    private func answerUsingFMOrFallback(question: String) async -> String {
        #if canImport(FoundationModels)
        if #available(iOS 18.0, *) {
            do {
                return try await FMFacade.shared.generateText(for: question)
            } catch {
                return "FM hiba: \(error.localizedDescription)"
            }
        } else {
            return "Foundation Models is available only on iOS 18 or later."
        }
        #else
        
        return "(fallback) - Foundation Models is not available in this environment."
        #endif
    }

    // MARK: - Speech to text
    @MainActor
    @available(iOS 10.0, *)
    private func recordAndTranscribe(maxDuration: TimeInterval = 15) async throws -> String {
        try await ensureSpeechAuthorization()
        try await ensureMicrophonePermission()

        guard let recognizer = SFSpeechRecognizer() else {
            throw TranscriptError.recognizerUnavailable
        }
        guard recognizer.isAvailable else {
            throw TranscriptError.recognizerUnavailable
        }

        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.record, mode: .measurement, options: [.duckOthers])
        try audioSession.setActive(true, options: .notifyOthersOnDeactivation)

        let audioEngine = AVAudioEngine()
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true

        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { buffer, _ in
            request.append(buffer)
        }

        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            inputNode.removeTap(onBus: 0)
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            throw error
        }

        return try await withCheckedThrowingContinuation { continuation in
            var finished = false
            var timeoutWorkItem: DispatchWorkItem?
            var recognitionTask: SFSpeechRecognitionTask?
            var latestPartial: String?
            var silenceWorkItem: DispatchWorkItem?
            let silenceTimeout: TimeInterval = 1.5

            func stopAudioSession() {
                audioEngine.stop()
                inputNode.removeTap(onBus: 0)
                request.endAudio()
                recognitionTask?.cancel()
                timeoutWorkItem?.cancel()
                silenceWorkItem?.cancel()
                try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            }

            func finish(_ result: Result<String, Error>) {
                guard !finished else { return }
                finished = true
                stopAudioSession()
                switch result {
                case .success(let text):
                    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    if trimmed.isEmpty {
                        continuation.resume(throwing: TranscriptError.noSpeechDetected)
                    } else {
                        continuation.resume(returning: trimmed)
                    }
                case .failure(let error):
                    continuation.resume(throwing: error)
                }
            }

            recognitionTask = recognizer.recognitionTask(with: request) { result, error in
                if let error = error {
                    finish(.failure(error))
                    return
                }

                guard let result = result else { return }

                let text = result.bestTranscription.formattedString
                if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    latestPartial = text
                    silenceWorkItem?.cancel()
                    let workItem = DispatchWorkItem {
                        if let partial = latestPartial?.trimmingCharacters(in: .whitespacesAndNewlines),
                           !partial.isEmpty {
                            finish(.success(partial))
                        }
                    }
                    silenceWorkItem = workItem
                    DispatchQueue.main.asyncAfter(deadline: .now() + silenceTimeout, execute: workItem)
                }

                if result.isFinal {
                    finish(.success(text))
                }
            }

            let workItem = DispatchWorkItem {
                if let partial = latestPartial?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !partial.isEmpty {
                    finish(.success(partial))
                } else {
                    finish(.failure(TranscriptError.timeout))
                }
            }
            timeoutWorkItem = workItem
            DispatchQueue.main.asyncAfter(deadline: .now() + maxDuration, execute: workItem)
        }
    }

    @MainActor
    @available(iOS 10.0, *)
    private func ensureSpeechAuthorization() async throws {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized:
            return
        case .denied:
            throw TranscriptError.speechPermissionDenied
        case .restricted:
            throw TranscriptError.recognizerUnavailable
        case .notDetermined:
            let status = await withCheckedContinuation { continuation in
                SFSpeechRecognizer.requestAuthorization { authStatus in
                    continuation.resume(returning: authStatus)
                }
            }
            if status != .authorized {
                throw TranscriptError.speechPermissionDenied
            }
        @unknown default:
            throw TranscriptError.recognizerUnavailable
        }
    }

    @MainActor
    private func ensureMicrophonePermission() async throws {
        let audioSession = AVAudioSession.sharedInstance()
        switch audioSession.recordPermission {
        case .granted:
            return
        case .denied:
            throw TranscriptError.microphonePermissionDenied
        case .undetermined:
            let granted = await withCheckedContinuation { continuation in
                audioSession.requestRecordPermission { ok in
                    continuation.resume(returning: ok)
                }
            }
            if !granted {
                throw TranscriptError.microphonePermissionDenied
            }
        @unknown default:
            throw TranscriptError.microphonePermissionDenied
        }
    }
}

#if canImport(FoundationModels)
import FoundationModels

@available(iOS 18.0, *)
actor FMFacade {
    static let shared = FMFacade()

    // Based on the sample:
    // - LanguageModelSession keeps track of the conversation
    // - GenerationOptions provides configuration
    private let session: LanguageModelSession

    // Default configuration (system prompt, temperature, max token)
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

    // Optional streaming helper if streaming output is required
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

    // Optional availability check for the on-device model
    static var isModelAvailable: Bool {
        SystemLanguageModel.default.isAvailable
    }
}
#endif

private enum TranscriptError: LocalizedError {
    case speechPermissionDenied
    case microphonePermissionDenied
    case recognizerUnavailable
    case timeout
    case noSpeechDetected

    var errorDescription: String? {
        switch self {
        case .speechPermissionDenied:
            return "Speech recognition permission was denied. Please enable it in Settings."
        case .microphonePermissionDenied:
            return "Microphone permission was denied. Please enable it in Settings."
        case .recognizerUnavailable:
            return "Speech recognizer is not available on this device."
        case .timeout:
            return "Timed out before a final transcription was received."
        case .noSpeechDetected:
            return "No speech was detected."
    }
}
}
