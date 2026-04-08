import Foundation

/// Build metadata stamped by the yalc push script.
/// The BUILD_ID placeholder is replaced with the git short hash at push time.
enum PluginBuildInfo {
    static let BUILD_ID: String = "dev"
}
