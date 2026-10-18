; Database and profile files are created by Java in the current user's LocalAppData.
; Python packaging and production credential setup are still required before release.
#define AppFolder SourcePath + "..\dist\DungeonMice"
#define AppIcon SourcePath + "..\src\main\resources\images\dungeon-mouse.ico"
#define VlcInstaller SourcePath + "..\dist\vlc-3.0.23-win64.exe"
#if !FileExists(VlcInstaller)
  #undef VlcInstaller
  #define VlcInstaller AppFolder + "\vlc-3.0.23-win64.exe"
#endif

[Setup]
AppId=DungeonMice
AppName=Dungeon Mice
AppVersion=1.0.0
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
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "Crear acceso directo en el escritorio"; Flags: unchecked

[Files]
Source: "{#AppFolder}\*"; DestDir: "{app}"; Excludes: "\data\*,\videos\*,\vlc-*-win64.exe,\vlc-setup.exe"; Flags: ignoreversion recursesubdirs
Source: "{#VlcInstaller}"; DestDir: "{tmp}"; DestName: "vlc-setup.exe"; Flags: deleteafterinstall

[Icons]
Name: "{group}\Dungeon Mice"; Filename: "{app}\DungeonMice.exe"; WorkingDir: "{app}"
Name: "{group}\Desinstalar Dungeon Mice"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Dungeon Mice"; Filename: "{app}\DungeonMice.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{tmp}\vlc-setup.exe"; StatusMsg: "Instalando VLC..."; Flags: waituntilterminated
Filename: "{app}\DungeonMice.exe"; Description: "Abrir Dungeon Mice"; WorkingDir: "{app}"; Flags: postinstall nowait skipifsilent

; No UninstallDelete: never remove user databases, profile photos or external videos.
