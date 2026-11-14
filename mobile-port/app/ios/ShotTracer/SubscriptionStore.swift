import Foundation
import SwiftUI
import StoreKit
import Security
import CryptoKit

private let threeDays: TimeInterval=3*86400
private struct TrialRecord: Codable {
    var started: Double, until: Double, seen: Double
    var introduced: Bool?=false
    var device=UUID().uuidString
    var used: Set<String>=[]
}

@MainActor final class SubscriptionAccess: ObservableObject {
    static let productID="com.ajc.shottracer.monthly"
    private static let testPublicKey="MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEqWZYqtctcRgRwJK/F7BkEB9j35lGhap5LqaOAlQGV6IpH3Fo3Av0i3lRaUKpqYK1zFb3wJ+lrmbkUo7fuR9tBw=="
    @Published var product: Product?
    @Published var showing=false
    @Published var now=Date().timeIntervalSince1970
    @Published var storeUntil=0.0
    @Published var message: String?
    private var record: TrialRecord
    private var writable=true
    private var updates: Task<Void,Never>?
    private var clockTask: Task<Void,Never>?
    private let bootTick=ProcessInfo.processInfo.systemUptime
    private var bootWall=Date().timeIntervalSince1970
    private let service=(Bundle.main.bundleIdentifier ?? "ShotTracer")+".access-v1"
    static var testingCodes: Bool {
        #if DEBUG && SHOTTRACER_TEST_CODES
        return Bundle.main.bundleIdentifier?.hasSuffix(".tester")==true
        #else
        return false
        #endif
    }
    init() {
        let date=Date().timeIntervalSince1970
        record=TrialRecord(started:date,until:date+threeDays,seen:date)
        var value: CFTypeRef?
        let query:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:"trial",kSecReturnData as String:true]
        let status=SecItemCopyMatching(query as CFDictionary,&value)
        if status==errSecSuccess {
            if let data=value as? Data,let saved=try? JSONDecoder().decode(TrialRecord.self,from:data) { record=saved }
            else { writable=false }
        } else if status != errSecItemNotFound { writable=false }
        bootWall=max(date,record.seen);now=bootWall
        if writable { writable=save() }
        showing=record.introduced != true
    }
    private var effectiveNow: Double { max(Date().timeIntervalSince1970,bootWall+ProcessInfo.processInfo.systemUptime-bootTick) }
    var allowed: Bool { Date().timeIntervalSince1970+300>=record.seen && ((writable && effectiveNow<record.until) || effectiveNow<storeUntil) }
    var remainingHours: Int { max(0,Int(ceil((record.until-effectiveNow)/3600))) }
    var device: String { record.device }
    var needsIntroduction: Bool { record.introduced != true }
    func continueToApp() { record.introduced=true; _=save(); showing=false }
    private func save() -> Bool {
        record.seen=max(record.seen,now)
        guard let data=try? JSONEncoder().encode(record) else { return false }
        let query:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:"trial"]
        let result=SecItemUpdate(query as CFDictionary,[kSecValueData as String:data] as CFDictionary)
        if result==errSecItemNotFound {
            var add=query;add[kSecValueData as String]=data;add[kSecAttrAccessible as String]=kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            return SecItemAdd(add as CFDictionary,nil)==errSecSuccess
        }
        return result==errSecSuccess
    }
    func start() async {
        guard updates==nil else { return }
        updates=Task { for await event in Transaction.updates {
            if case .verified(let transaction)=event,transaction.productID==Self.productID {
                await transaction.finish();await refresh();message=nil
            }
        } }
        clockTask=Task { while !Task.isCancelled {
            now=max(Date().timeIntervalSince1970,bootWall+ProcessInfo.processInfo.systemUptime-bootTick)
            if writable { writable=save() }
            try? await Task.sleep(for:.seconds(30))
        } }
        do { product=try await Product.products(for:[Self.productID]).first { $0.type == .autoRenewable && $0.subscription?.subscriptionPeriod.unit == .month && $0.subscription?.subscriptionPeriod.value == 1 } }
        catch { message=NSLocalizedString("access.unavailable",comment:"") }
        await refresh()
    }
    func refresh() async {
        now=max(Date().timeIntervalSince1970,bootWall+ProcessInfo.processInfo.systemUptime-bootTick)
        var expiry=0.0
        for await event in Transaction.currentEntitlements {
            if case .verified(let transaction)=event,transaction.productID==Self.productID,transaction.revocationDate==nil,!transaction.isUpgraded {
                expiry=max(expiry,transaction.expirationDate?.timeIntervalSince1970 ?? 0)
            }
        }
        if let statuses=try? await product?.subscription?.status {
            for status in statuses where status.state == .inGracePeriod {
                if case .verified(let transaction)=status.transaction,transaction.productID==Self.productID,
                   case .verified(let renewal)=status.renewalInfo {
                    expiry=max(expiry,renewal.gracePeriodExpirationDate?.timeIntervalSince1970 ?? 0)
                }
            }
        }
        storeUntil=expiry
    }
    func purchase() async {
        guard let product else { return }
        do {
            switch try await product.purchase() {
            case .success(let event):
                guard case .verified(let transaction)=event,transaction.productID==Self.productID else { message=NSLocalizedString("access.unavailable",comment:"");return }
                await transaction.finish();await refresh();message=nil
            case .pending: message=NSLocalizedString("access.pending",comment:"")
            case .userCancelled: break
            @unknown default: break
            }
        } catch { message=NSLocalizedString("access.unavailable",comment:"") }
    }
    func restore() async {
        do { try await AppStore.sync();await refresh();message=nil }
        catch { message=NSLocalizedString("access.unavailable",comment:"") }
    }
    func redeemTest(_ code: String) -> Bool {
        #if DEBUG && SHOTTRACER_TEST_CODES
        now=effectiveNow
        guard Self.testingCodes,code.count<=4096,Date().timeIntervalSince1970+300>=record.seen else { return false }
        func decode(_ text: Substring) -> Data? {
            var s=String(text).replacingOccurrences(of:"-",with:"+").replacingOccurrences(of:"_",with:"/")
            s += String(repeating:"=",count:(4-s.count%4)%4);return Data(base64Encoded:s)
        }
        let parts=code.split(separator:".")
        guard parts.count==3,parts[0]=="ST3",let data=decode(parts[1]),let signatureData=decode(parts[2]),
              let publicData=Data(base64Encoded:Self.testPublicKey),let key=try? P256.Signing.PublicKey(derRepresentation:publicData),
              let signature=try? P256.Signing.ECDSASignature(derRepresentation:signatureData),key.isValidSignature(signature,for:data),
              let p=(try? JSONSerialization.jsonObject(with:data)) as? [String:Any],p["v"] as? Int==1,p["aud"] as? String=="ios",p["device"] as? String==record.device,
              let nonce=p["nonce"] as? String,!nonce.isEmpty,!record.used.contains(nonce),let issued=p["issued"] as? Double,let limit=p["redeem_before"] as? Double,
              p["seconds"] as? Double==threeDays,issued<=now+300,now<limit,limit>issued,limit-issued<=7*86400 else { return false }
        let old=record;record.until=max(now,record.until)+threeDays;record.used.insert(nonce)
        guard save() else { record=old;return false }
        objectWillChange.send();return true
        #else
        return false
        #endif
    }
}

struct SubscriptionRootView: View {
    @StateObject private var access=SubscriptionAccess()
    @Environment(\.scenePhase) private var phase
    var body: some View {
        ZStack {
            EditorView().environmentObject(access).disabled(!access.allowed)
            if !access.allowed { SubscriptionScreen(access:access).background(.background) }
        }.sheet(isPresented:$access.showing) { SubscriptionScreen(access:access) }
            .task { await access.start() }
            .onChange(of:phase) { _,p in if p == .active { Task { await access.refresh() } } }
    }
}
struct SubscriptionScreen: View {
    @ObservedObject var access: SubscriptionAccess
    @State private var redeeming=false
    @State private var code=""
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(access.storeUntil>access.now ? NSLocalizedString("access.subscribed",comment:"") : String(format:NSLocalizedString(access.allowed ? "access.available" : "access.expired",comment:""),access.remainingHours))
                    Text("access.referencePrice").font(.footnote)
                    if let product=access.product {
                        Button(String(format:NSLocalizedString("access.subscribe",comment:""),product.displayPrice)) { Task { await access.purchase() } }
                    } else { Text("access.unavailable").foregroundStyle(.secondary) }
                    Button("access.restore") { Task { await access.restore() } }
                    Link("access.manage",destination:URL(string:"https://apps.apple.com/account/subscriptions")!)
                    Button("access.redeem") { redeeming=true }
                    if let message=access.message { Text(message).foregroundStyle(.secondary) }
                }
                if SubscriptionAccess.testingCodes {
                    Section("access.testCode") {
                        Text(String(format:NSLocalizedString("access.device",comment:""),access.device)).textSelection(.enabled)
                        TextField("access.code",text:$code).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button("access.redeem") { access.message=NSLocalizedString(access.redeemTest(code.trimmingCharacters(in:.whitespacesAndNewlines)) ? "access.extended" : "access.invalidCode",comment:"") }
                    }
                }
                Section { Text("access.terms").font(.footnote) }
                if access.allowed { Button("access.continue") { access.continueToApp() } }
            }.navigationTitle("access.title")
        }.interactiveDismissDisabled(!access.allowed || access.needsIntroduction)
            .offerCodeRedemption(isPresented:$redeeming)
            .onChange(of:redeeming) { _,value in if !value { Task { await access.refresh() } } }
    }
}
