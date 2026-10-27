; Inno Setup script for the Windows installer. Build the app first (build.sh), then:
;   ISCC.exe /DAppVersion=0.1.0 /DSourceDir=C:\path\to\dist\windows\GBook /OC:\path\to\dist gbook.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\..\dist\windows\GBook"
#endif

[Setup]
AppId={{6B8B1F2E-3C1D-4E8A-9D1F-6A2B7C9E0F11}
AppName=GBook
AppVersion={#AppVersion}
AppVerName=GBook {#AppVersion}
AppPublisher=Gsweya
AppPublisherURL=https://github.com/Gsweya/eBook
AppSupportURL=https://github.com/Gsweya/eBook/issues
DefaultDirName={autopf}\GBook
DefaultGroupName=GBook
DisableProgramGroupPage=yes
OutputBaseFilename=GBook-{#AppVersion}-setup
SetupIconFile=gbook.ico
UninstallDisplayIcon={app}\gbook.ico
LicenseFile=..\..\..\LICENSE
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Installs for the current user without admin rights unless the user chooses all users.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ChangesAssociations=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\GBook"; Filename: "{app}\bin\gbook.exe"; IconFilename: "{app}\gbook.ico"
Name: "{autodesktop}\GBook"; Filename: "{app}\bin\gbook.exe"; IconFilename: "{app}\gbook.ico"; Tasks: desktopicon

[Registry]
; "Open with GBook" for EPUB and PDF, without taking over the default PDF app.
Root: HKA; Subkey: "Software\Classes\GBook.Book"; ValueType: string; ValueName: ""; ValueData: "Book"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\GBook.Book\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\gbook.ico"
Root: HKA; Subkey: "Software\Classes\GBook.Book\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\bin\gbook.exe"" ""%1"""
Root: HKA; Subkey: "Software\Classes\.epub\OpenWithProgids"; ValueType: string; ValueName: "GBook.Book"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.pdf\OpenWithProgids"; ValueType: string; ValueName: "GBook.Book"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\gbook.exe\SupportedTypes"; ValueType: string; ValueName: ".epub"; ValueData: ""; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\gbook.exe\SupportedTypes"; ValueType: string; ValueName: ".pdf"; ValueData: ""

[Run]
Filename: "{app}\bin\gbook.exe"; Description: "{cm:LaunchProgram,GBook}"; Flags: nowait postinstall skipifsilent
