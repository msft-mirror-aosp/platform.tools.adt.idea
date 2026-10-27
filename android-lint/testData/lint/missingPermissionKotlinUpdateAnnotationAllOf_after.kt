package p1.p2

import android.Manifest
import android.os.DropBoxManager
import androidx.annotation.RequiresPermission

@Suppress("unused")
class LocationTest {
  @RequiresPermission(allOf = ["foo.bar.baz", Manifest.permission.READ_DROPBOX_DATA, Manifest.permission.PACKAGE_USAGE_STATS])
  fun test(manager: DropBoxManager) {
    manager.getNext<caret>Entry("tag", 8675309L)
  }
}