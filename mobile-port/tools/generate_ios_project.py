"""Generate a dependency-free Xcode project; no developer account or secret is embedded."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/ios/ShotTracer.xcodeproj"


def main() -> None:
    objects: list[str] = []
    counter = 0

    def add(body: str) -> str:
        nonlocal counter
        counter += 1
        key = f"{counter:024X}"
        objects.append(f"{key} = {{ {body} }};")
        return key

    def file(path: str, kind: str) -> str:
        return add(f'isa = PBXFileReference; lastKnownFileType = {kind}; path = "{path}"; sourceTree = "<group>";')

    sources = [file(f"ShotTracer/{name}.swift", "sourcecode.swift")
               for name in ("ShotTracerApp", "EditorModel", "TrackingSession")]
    sources.append(file("../../core/src/shottracer.cpp", "sourcecode.cpp.cpp"))
    headers = [file("ShotTracer/Bridge.h", "sourcecode.c.h"), file("../../core/include/shottracer.h", "sourcecode.c.h")]
    languages = [file(f"ShotTracer/{lang}.lproj/Localizable.strings", "text.plist.strings") for lang in ("en", "ko")]
    localized = add(f'isa = PBXVariantGroup; children = ({", ".join(languages)}); name = Localizable.strings; sourceTree = "<group>";')
    product = add('isa = PBXFileReference; explicitFileType = wrapper.application; path = ShotTracer.app; sourceTree = BUILT_PRODUCTS_DIR;')
    builds = [add(f"isa = PBXBuildFile; fileRef = {source};") for source in sources]
    resource = add(f"isa = PBXBuildFile; fileRef = {localized};")
    source_phase = add(f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({", ".join(builds)}); runOnlyForDeploymentPostprocessing = 0;')
    resource_phase = add(f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({resource}); runOnlyForDeploymentPostprocessing = 0;')
    frameworks = add('isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
    products = add(f'isa = PBXGroup; children = ({product}); name = Products; sourceTree = "<group>";')
    group = add(f'isa = PBXGroup; children = ({", ".join(sources + headers + [localized, products])}); sourceTree = "<group>";')
    common = '''CLANG_ENABLE_MODULES = YES; CLANG_CXX_LANGUAGE_STANDARD = "c++17";
        IPHONEOS_DEPLOYMENT_TARGET = 26.6.2; SDKROOT = iphoneos; SWIFT_VERSION = 5.0;
        SWIFT_OBJC_BRIDGING_HEADER = "ShotTracer/Bridge.h";
        HEADER_SEARCH_PATHS = "$(SRCROOT)/../../core/include";
        PRODUCT_BUNDLE_IDENTIFIER = com.ajc.shottracer.dev; PRODUCT_NAME = ShotTracer;
        MARKETING_VERSION = 0.0.1; CURRENT_PROJECT_VERSION = 1;
        GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_CFBundleDisplayName = ShotTracer;
        INFOPLIST_KEY_LSApplicationCategoryType = "public.app-category.sports";
        INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES;
        INFOPLIST_KEY_UILaunchScreen_Generation = YES;
        INFOPLIST_KEY_UIFileSharingEnabled = YES;
        INFOPLIST_KEY_LSSupportsOpeningDocumentsInPlace = YES;
        TARGETED_DEVICE_FAMILY = 1; CODE_SIGN_STYLE = Automatic;'''
    configs = [add(f'isa = XCBuildConfiguration; name = {name}; buildSettings = {{ {common} SWIFT_OPTIMIZATION_LEVEL = "{optimization}"; }};')
               for name, optimization in (("Debug", "-Onone"), ("Release", "-O"))]
    config_list = add(f'isa = XCConfigurationList; buildConfigurations = ({", ".join(configs)}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
    target = add(f'''isa = PBXNativeTarget; buildConfigurationList = {config_list};
        buildPhases = ({source_phase}, {frameworks}, {resource_phase}); buildRules = (); dependencies = ();
        name = ShotTracer; productName = ShotTracer; productReference = {product}; productType = "com.apple.product-type.application";''')
    project_configs = [add(f'isa = XCBuildConfiguration; name = {name}; buildSettings = {{}};') for name in ("Debug", "Release")]
    project_config_list = add(f'isa = XCConfigurationList; buildConfigurations = ({", ".join(project_configs)}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
    project = add(f'''isa = PBXProject; attributes = {{ LastUpgradeCheck = 2720; }};
        buildConfigurationList = {project_config_list}; compatibilityVersion = "Xcode 14.0";
        developmentRegion = en; knownRegions = (en, ko, Base); mainGroup = {group}; productRefGroup = {products};
        projectDirPath = ""; projectRoot = ""; targets = ({target});''')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "project.pbxproj").write_text("// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n" +
                                       "\n".join(objects) + f"\n}}; rootObject = {project}; }}\n")
    print(OUT)


if __name__ == "__main__":
    main()
