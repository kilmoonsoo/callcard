import SwiftUI
import MessageUI

/// iOS 정책상 문자는 자동 발송할 수 없어, 내용이 채워진 메시지 작성 화면을 띄웁니다. 사용자가 [전송]을 누릅니다.
struct MessageComposer: UIViewControllerRepresentable {
    let recipient: String
    let subject: String
    let body: String
    let jpeg: Data?
    let onFinish: (MessageComposeResult) -> Void

    static var canSend: Bool { MFMessageComposeViewController.canSendText() }

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let vc = MFMessageComposeViewController()
        vc.messageComposeDelegate = context.coordinator
        vc.recipients = [recipient]
        vc.body = body
        if MFMessageComposeViewController.canSendSubject(), !subject.isEmpty { vc.subject = subject }
        if let jpeg, MFMessageComposeViewController.canSendAttachments() {
            vc.addAttachmentData(jpeg, typeIdentifier: "public.jpeg", filename: "명함.jpg")
        }
        return vc
    }

    func updateUIViewController(_ vc: MFMessageComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let onFinish: (MessageComposeResult) -> Void
        init(onFinish: @escaping (MessageComposeResult) -> Void) { self.onFinish = onFinish }
        func messageComposeViewController(_ controller: MFMessageComposeViewController,
                                          didFinishWith result: MessageComposeResult) {
            controller.dismiss(animated: true)
            onFinish(result)
        }
    }
}
