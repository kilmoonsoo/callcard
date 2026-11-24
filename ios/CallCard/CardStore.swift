import Foundation
import UIKit

/// 설정과 '하루 1회' 기록. 모든 데이터는 이 아이폰 안에만 저장됩니다.
final class CardStore: ObservableObject {
    private let d = UserDefaults.standard

    @Published var subject: String { didSet { d.set(subject, forKey: "subject") } }
    @Published var intro: String { didSet { d.set(intro, forKey: "intro") } }
    /// 부재중 전화용 소개글. 비워두면 기본 소개글을 보냅니다.
    @Published var missedIntro: String { didSet { d.set(missedIntro, forKey: "missedIntro") } }
    @Published var cardImage: UIImage?
    @Published var log: [String]

    private var cardURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("card.jpg")
    }

    init() {
        subject = d.string(forKey: "subject") ?? "명함 드립니다"
        intro = d.string(forKey: "intro") ?? ""
        missedIntro = d.string(forKey: "missedIntro") ?? ""
        log = d.stringArray(forKey: "log") ?? []
        if let data = try? Data(contentsOf: cardURL) { cardImage = UIImage(data: data) }
    }

    func introFor(missed: Bool) -> String {
        missed && !missedIntro.isEmpty ? missedIntro : intro
    }

    var cardJPEG: Data? { try? Data(contentsOf: cardURL) }

    /// MMS 용량(약 300KB 이하)에 맞게 줄여 저장
    func saveCard(_ image: UIImage) {
        var side: CGFloat = 1280
        var data = Data()
        repeat {
            let scale = min(1, side / max(image.size.width, image.size.height))
            let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
            let resized = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            var q: CGFloat = 0.9
            repeat {
                data = resized.jpegData(compressionQuality: q) ?? Data()
                q -= 0.1
            } while data.count > 280_000 && q >= 0.5
            side -= 200
        } while data.count > 280_000 && side >= 480
        try? data.write(to: cardURL)
        cardImage = UIImage(data: data)
    }

    // MARK: 동일 번호 하루 1회
    private func today() -> String {
        let f = DateFormatter(); f.dateFormat = "yyyyMMdd"; return f.string(from: Date())
    }

    func alreadySentToday(_ number: String) -> Bool {
        (d.dictionary(forKey: "sentDay") as? [String: String])?[number] == today()
    }

    func markSent(_ number: String) {
        let t = today()
        var map = (d.dictionary(forKey: "sentDay") as? [String: String] ?? [:]).filter { $0.value == t }
        map[number] = t
        d.set(map, forKey: "sentDay")
        addLog(number, "발송 완료")
    }

    func addLog(_ number: String, _ msg: String) {
        let f = DateFormatter(); f.dateFormat = "MM/dd HH:mm"
        log.insert("\(f.string(from: Date()))  \(Phone.mask(number))  \(msg)", at: 0)
        log = Array(log.prefix(30))
        d.set(log, forKey: "log")
    }
}

enum Phone {
    static func normalize(_ raw: String) -> String {
        var s = raw.filter { $0.isNumber || $0 == "+" }
        if s.hasPrefix("+82") { s = "0" + s.dropFirst(3) }
        return s.filter { $0.isNumber }
    }
    static func isKoreanMobile(_ n: String) -> Bool {
        n.range(of: #"^01[016789]\d{7,8}$"#, options: .regularExpression) != nil
    }
    static func mask(_ n: String) -> String {
        n.count >= 8 ? "\(n.prefix(3))-****-\(n.suffix(4))" : n
    }
}
