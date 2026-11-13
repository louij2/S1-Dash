#if canImport(CarPlay) && !targetEnvironment(macCatalyst)
import CarPlay

/// The CarPlay screen: live readings and the Haldex modes as lists, the only kind of
/// screen CarPlay allows here (no dials). It only appears where the app has the CarPlay
/// "driving task" entitlement: for now that's simulator builds, see CarPlay-Simulator.entitlements.
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private var timer: Timer?
    private let live = CPListTemplate(title: "Live", sections: [])
    private let awd = CPListTemplate(title: "All-wheel drive", sections: [])

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
        Hub.shared.acquireSources("carplay")
        live.tabTitle = "Live"
        live.tabImage = UIImage(systemName: "gauge.with.dots.needle.67percent")
        awd.tabTitle = "AWD"
        awd.tabImage = UIImage(systemName: "car")
        refresh()
        interfaceController.setRootTemplate(CPTabBarTemplate(templates: [live, awd]), animated: false, completion: nil)
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.refresh() }
        print("S1Dash: CarPlay connected")
    }

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        timer?.invalidate()
        timer = nil
        self.interfaceController = nil
        Hub.shared.releaseSources("carplay")
        print("S1Dash: CarPlay disconnected")
    }

    private func refresh() {
        let data = DashData.shared
        let rows = data.readings().map { CPListItem(text: $0.label, detailText: $0.value) }
        live.updateSections([CPListSection(items: rows, header: data.source, sectionIndexTitle: nil)])
        let modes = DashData.modes.enumerated().map { index, name -> CPListItem in
            let item = CPListItem(text: name, detailText: index == data.mode ? "Active" : nil)
            item.handler = { [weak self] _, done in
                data.setMode(index)
                self?.refresh()
                done()
            }
            return item
        }
        awd.updateSections([CPListSection(items: modes)])
    }
}
#endif
