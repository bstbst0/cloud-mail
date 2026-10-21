import SwiftUI
import UIKit
import BackgroundTasks
import UserNotifications
import CloudMail

@main
struct CloudMailApp: App {
    @UIApplicationDelegateAdaptor(MailDelegate.self) var delegate
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup { MailView().ignoresSafeArea(.keyboard) }
            .onChange(of: phase) { phase in
                if phase == .active { IosLifecycle().refresh() }
                if phase == .background { MailDelegate.scheduleRefresh() }
            }
    }
}

struct MailView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

final class MailDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    static let taskID = "com.cloudmail.app.refresh"
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskID, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            Self.scheduleRefresh()
            let sync = BackgroundSync()
            refresh.expirationHandler = { sync.cancel() }
            sync.run { success in refresh.setTaskCompleted(success: success.boolValue) }
        }
        return true
    }
    static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: taskID)
        let minutes = max(15, min(1440, UserDefaults.standard.integer(forKey: "sync-minutes")))
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(minutes * 60))
        do { try BGTaskScheduler.shared.submit(request) } catch { /* OS may deny background time; foreground/manual sync remains available. */ }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if let value = response.notification.request.content.userInfo["messageId"] as? String, let id = Int64(value) { IosLifecycle().openMessage(id: id) }
        completionHandler()
    }
}
