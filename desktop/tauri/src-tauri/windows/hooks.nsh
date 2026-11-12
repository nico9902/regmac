; Disinstallazione di RegMac su Windows.
; Elimina Ollama e i modelli AI scaricati da RegMac (%APPDATA%\RegMac\ollama).
; Se l'utente spunta «Elimina i dati dell'applicazione», elimina anche i fascicoli (%APPDATA%\RegMac).
; Non fa nulla durante un aggiornamento (l'installatore nuovo disinstalla il vecchio con /UPDATE):
; altrimenti i modelli verrebbero riscaricati a ogni nuova versione.
!macro NSIS_HOOK_POSTUNINSTALL
  ${If} $UpdateMode <> 1
    SetShellVarContext current
    RMDir /r "$APPDATA\RegMac\ollama"
    ${If} $DeleteAppDataCheckboxState = 1
      RMDir /r "$APPDATA\RegMac"
    ${EndIf}
  ${EndIf}
!macroend
