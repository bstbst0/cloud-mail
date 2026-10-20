# Desktop branding

The PNG in `composeApp/src/desktopMain/resources/cloud-mail.png` and the ICO/ICNS files here use the same original envelope artwork as the iOS app icon and Android adaptive icon. They are source assets, not generated installers. The desktop window and each native installer load the appropriate format.

When changing the icon, update these exports together with the iOS asset catalog and Android vector. The master raster is the opaque 1024-pixel iOS marketing icon. ICO includes 16–256-pixel variants; ICNS includes its standard size variants. No image-generation or Pillow runtime dependency is shipped.

The Windows installer uses per-user installation, Start-menu/desktop shortcuts and a stable upgrade UUID. Keep that UUID for future upgrades of this product; change the package version for releases.
