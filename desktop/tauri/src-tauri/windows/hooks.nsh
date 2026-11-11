; Disinstallazione di RegMac su Windows.
; Elimina sempre Ollama e i modelli AI scaricati da RegMac (%APPDATA%\RegMac\ollama).
; Se l'utente spunta «Elimina i dati dell'applicazione», elimina anche i fascicoli (%APPDATA%\RegMac).
!macro NSIS_HOOK_POSTUNINSTALL
  RMDir /r "$APPDATA\RegMac\ollama"
  ${If} $DeleteAppDataCheckboxState = 1
    RMDir /r "$APPDATA\RegMac"
  ${EndIf}
!macroend
