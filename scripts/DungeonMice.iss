; Database and profile files are created by Java in the current user's LocalAppData.
; Build scripts/build-windows.ps1 first; this installer requires its Python backend.
#define AppFolder SourcePath + "..\dist\DungeonMice"
#define AppIcon SourcePath + "..\src\main\resources\images\dungeon-mouse.ico"
#define VlcInstaller SourcePath + "..\dist\vlc-3.0.23-win64.exe"
#if !FileExists(VlcInstaller)
  #undef VlcInstaller
  #define VlcInstaller AppFolder + "\vlc-3.0.23-win64.exe"
#endif
#if !FileExists(AppFolder + "\backend\bundle-manifest.json") || !FileExists(AppFolder + "\backend\runtime\python\python.exe") || !FileExists(AppFolder + "\backend\engine\src\RunExperiment_per_frame.py")
  #error Build the complete Windows package with scripts/build-windows.ps1 before compiling this installer.
#endif

[Setup]
AppId=DungeonMice
AppName=Dungeon Mice
AppVersion={#GetFileVersion(AppFolder + "\DungeonMice.exe")}
DefaultDirName={autopf}\DungeonMice
DefaultGroupName=Dungeon Mice
OutputDir=..\dist\installer
OutputBaseFilename=DungeonMiceSetup
SetupIconFile={#AppIcon}
UninstallDisplayIcon={app}\DungeonMice.exe
UninstallDisplayName=Dungeon Mice
Uninstallable=yes
CreateUninstallRegKey=yes
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
MinVersion=10.0
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "Crear acceso directo en el escritorio"; Flags: unchecked

[Files]
Source: "{#AppFolder}\*"; DestDir: "{app}"; Excludes: "\data\*,\videos\*,\vlc-*-win64.exe,\vlc-setup.exe,\backend\prerequisites\*"; Flags: ignoreversion recursesubdirs
Source: "{#VlcInstaller}"; DestDir: "{tmp}"; DestName: "vlc-setup.exe"; Flags: deleteafterinstall
Source: "{#AppFolder}\backend\prerequisites\VC_redist.x64.exe"; Flags: dontcopy

[Icons]
Name: "{group}\Dungeon Mice"; Filename: "{app}\DungeonMice.exe"; WorkingDir: "{app}"
Name: "{group}\Desinstalar Dungeon Mice"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Dungeon Mice"; Filename: "{app}\DungeonMice.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{tmp}\vlc-setup.exe"; StatusMsg: "Instalando VLC..."; Flags: waituntilterminated
Filename: "{app}\DungeonMice.exe"; Description: "Abrir Dungeon Mice"; WorkingDir: "{app}"; Flags: postinstall nowait skipifsilent

; No UninstallDelete: never remove user databases, profile photos or external videos.

[Code]
function NeedsVCRuntime: Boolean;
var Installed, Major, Minor, Build: Cardinal;
begin
  Result := True;
  if not RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Installed', Installed) then Exit;
  if Installed <> 1 then Exit;
  if not RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Major', Major) then Exit;
  if not RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Minor', Minor) then Exit;
  if not RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Bld', Build) then Exit;
  Result := (Major < 14) or ((Major = 14) and ((Minor < 51) or ((Minor = 51) and (Build < 36247))));
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var ExitCode: Integer;
begin
  Result := '';
  if not NeedsVCRuntime then Exit;
  ExtractTemporaryFile('VC_redist.x64.exe');
  if not Exec(ExpandConstant('{tmp}\VC_redist.x64.exe'), '/install /quiet /norestart', '', SW_HIDE, ewWaitUntilTerminated, ExitCode) then begin
    Result := 'No se pudo iniciar el prerrequisito Microsoft Visual C++.';
    Exit;
  end;
  if ExitCode = 3010 then NeedsRestart := True
  else if ((ExitCode <> 0) and (ExitCode <> 1638)) or NeedsVCRuntime then
    Result := 'No se pudo preparar Microsoft Visual C++. Codigo: ' + IntToStr(ExitCode);
end;
