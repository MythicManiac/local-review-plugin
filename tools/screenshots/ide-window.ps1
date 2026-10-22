# Drives and captures ONLY the sandbox IDE window (never the whole desktop). Windows only.
#   powershell -ExecutionPolicy Bypass -File ide-window.ps1 <cmd> [args]
#   top | untop            keep the IDE window above others while capturing / release it
#   place <w> <h>          resize and move the window (Marketplace screenshots: 1280 800)
#   shot <file.png>        save the window rectangle as PNG
#   click <x> <y>          left click at window-relative coordinates
#   ctrlclick <x> <y>      Ctrl+click (Go to Declaration by mouse)
#   keys <SendKeys text>   send keystrokes to the focused window
# The window is found by title prefix (default "orders-service", the demo project name; override with -Title).
param([string]$cmd, [string]$a1, [string]$a2, [string]$a3, [string]$Title = "orders-service")
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public class W {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int hh, bool r);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@
$script:found = [IntPtr]::Zero
$prefix = $Title
$cb = [W+EnumProc]{ param($h, $l)
  if ([W]::IsWindowVisible($h)) {
    $sb = New-Object System.Text.StringBuilder 512; [void][W]::GetWindowText($h, $sb, 512)
    if ($sb.ToString().StartsWith($prefix)) { $script:found = $h } }
  return $true }
[void][W]::EnumWindows($cb, [IntPtr]::Zero); $h = $script:found
if ($h -eq [IntPtr]::Zero) { "NO IDE WINDOW"; exit 2 }
# Visible frame (DWM extended bounds): GetWindowRect also includes invisible resize borders that show whatever is behind.
function Rect { $r = New-Object W+RECT; if ([W]::DwmGetWindowAttribute($h, 9, [ref]$r, 16) -ne 0) { [void][W]::GetWindowRect($h, [ref]$r) }; return $r }
# Input is only ever sent while the IDE window is verifiably the foreground window; otherwise abort (exit 3).
function SameProcess($a, $b) { $pa = 0; $pb = 0; [void][W]::GetWindowThreadProcessId($a, [ref]$pa); [void][W]::GetWindowThreadProcessId($b, [ref]$pb); return ($pa -ne 0 -and $pa -eq $pb) }
function Focus {
  # The IDE's own dialogs/popups are separate top-level windows of the same process: they count as "the IDE".
  if (SameProcess ([W]::GetForegroundWindow()) $h) { return }
  [void][W]::ShowWindow($h, 9)
  [W]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [W]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)  # tap ALT: lets SetForegroundWindow through
  [void][W]::SetForegroundWindow($h); Start-Sleep -Milliseconds 300
  if (-not (SameProcess ([W]::GetForegroundWindow()) $h)) { "IDE WINDOW NOT FOCUSED - input NOT sent"; exit 3 }
}
function Outer { $r = New-Object W+RECT; [void][W]::GetWindowRect($h, [ref]$r); return $r }
switch ($cmd) {
  "top"   { [void][W]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 3); "topmost" }
  "untop" { [void][W]::SetWindowPos($h, [IntPtr](-2), 0, 0, 0, 0, 3); "untopped" }
  "place" { [void][W]::ShowWindow($h, 9); [void][W]::MoveWindow($h, 100, 50, [int]$a1, [int]$a2, $true)
            $o = Outer; $v = Rect   # grow the outer window by the invisible border so the VISIBLE frame is exactly w x h
            $dw = ($o.R - $o.L) - ($v.R - $v.L); $dh = ($o.B - $o.T) - ($v.B - $v.T)
            [void][W]::MoveWindow($h, 100 - [int]($dw / 2), 50, [int]$a1 + $dw, [int]$a2 + $dh, $true); $v = Rect
            "placed visible " + ($v.R - $v.L) + "x" + ($v.B - $v.T) }
  "shot"  { $r = Rect; $w = $r.R - $r.L; $hh = $r.B - $r.T
            $bmp = New-Object System.Drawing.Bitmap $w, $hh; $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size $w, $hh))
            $bmp.Save($a1, [System.Drawing.Imaging.ImageFormat]::Png); "$w x $hh" }
  "click" { Focus; $r = Rect; [void][W]::SetCursorPos($r.L + [int]$a1, $r.T + [int]$a2); Start-Sleep -Milliseconds 120
            [W]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero); [W]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero); "clicked" }
  "ctrlclick" { Focus; $r = Rect; [void][W]::SetCursorPos($r.L + [int]$a1, $r.T + [int]$a2); Start-Sleep -Milliseconds 150
            [W]::keybd_event(0x11, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 100
            [W]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero); [W]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 100
            [W]::keybd_event(0x11, 0, 2, [UIntPtr]::Zero); "ctrl-clicked" }
  "dclick" { Focus; $r = Rect; [void][W]::SetCursorPos($r.L + [int]$a1, $r.T + [int]$a2); Start-Sleep -Milliseconds 120
            for ($i = 0; $i -lt 2; $i++) { [W]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero); [W]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60 }; "double-clicked" }
  "move"  { $r = Rect; [void][W]::SetCursorPos($r.L + [int]$a1, $r.T + [int]$a2); "moved" }
  "keys"  { Focus; Start-Sleep -Milliseconds 100; [System.Windows.Forms.SendKeys]::SendWait($a1); "sent" }
}
