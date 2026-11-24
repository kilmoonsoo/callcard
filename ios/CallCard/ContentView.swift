import SwiftUI
import PhotosUI

struct ContentView: View {
    @StateObject private var store = CardStore()
    @State private var number = ""
    @State private var missed = false
    @State private var showComposer = false
    @State private var confirmResend = false
    @State private var photoItem: PhotosPickerItem?
    @State private var alert: String?

    private var normalized: String { Phone.normalize(number) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        TextField("상대 번호", text: $number).keyboardType(.phonePad)
                        PasteButton(payloadType: String.self) { items in
                            if let s = items.first { number = Phone.normalize(s) }
                        }
                        .labelStyle(.iconOnly)
                    }
                    Picker("통화 유형", selection: $missed) {
                        Text("통화 종료").tag(false)
                        Text("부재중").tag(true)
                    }
                    .pickerStyle(.segmented)
                    Button("명함 보내기") { trySend() }
                        .bold()
                        .disabled(normalized.isEmpty)
                } header: {
                    Text("보내기")
                } footer: {
                    Text("통화 후 [최근 통화]에서 ⓘ → 번호를 길게 눌러 복사 → 이 앱에서 붙여넣기 → [명함 보내기] → 메시지 화면에서 [전송]. iPhone은 정책상 자동 발송이 불가능합니다.")
                }

                Section("명함 이미지") {
                    if let img = store.cardImage {
                        Image(uiImage: img).resizable().scaledToFit().frame(maxHeight: 180)
                    }
                    PhotosPicker("이미지 선택", selection: $photoItem, matching: .images)
                }

                Section("제목") { TextField("제목", text: $store.subject) }

                Section("소개글") {
                    TextEditor(text: $store.intro).frame(minHeight: 120)
                }

                Section {
                    TextEditor(text: $store.missedIntro).frame(minHeight: 100)
                } header: {
                    Text("부재중 전화용 소개글")
                } footer: {
                    Text("비워두면 위의 소개글을 그대로 보냅니다.")
                }

                Section("최근 기록") {
                    if store.log.isEmpty { Text("아직 기록이 없습니다.").foregroundStyle(.secondary) }
                    ForEach(store.log, id: \.self) { Text($0).font(.footnote.monospaced()) }
                }
            }
            .navigationTitle("콜명함")
            .onChange(of: photoItem) { item in
                Task {
                    if let data = try? await item?.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                        store.saveCard(img)
                    }
                }
            }
            .sheet(isPresented: $showComposer) {
                MessageComposer(recipient: normalized, subject: store.subject, body: store.introFor(missed: missed),
                                jpeg: store.cardJPEG) { result in
                    if result == .sent { store.markSent(normalized); number = "" }
                    else if result == .failed { store.addLog(normalized, "발송 실패") }
                }
                .ignoresSafeArea()
            }
            .confirmationDialog("오늘 이미 이 번호로 보냈습니다. 다시 보낼까요?", isPresented: $confirmResend, titleVisibility: .visible) {
                Button("다시 보내기") { showComposer = true }
                Button("취소", role: .cancel) {}
            }
            .alert(alert ?? "", isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } })) {
                Button("확인", role: .cancel) {}
            }
        }
    }

    private func trySend() {
        guard MessageComposer.canSend else { alert = "이 기기에서는 문자를 보낼 수 없습니다."; return }
        guard store.cardImage != nil || !store.introFor(missed: missed).isEmpty else { alert = "명함 이미지나 소개글을 먼저 설정해 주세요."; return }
        if store.alreadySentToday(normalized) { confirmResend = true } else { showComposer = true }
    }
}
