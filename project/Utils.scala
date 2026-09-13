object Utils {

  lazy val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("windows")

  lazy val npm: String = if (isWindows) "npm.cmd" else "npm"

  lazy val npx: String = if (isWindows) "npx.cmd" else "npx"

}
