package me.tarry.app.ui.permissions

/** A [PermissionChecker] whose snapshot the test sets directly. */
class FakePermissionChecker(var snapshot: PermissionSnapshot = PermissionSnapshot()) : PermissionChecker {
    override fun check(): PermissionSnapshot = snapshot
}
