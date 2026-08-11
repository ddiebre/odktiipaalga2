@echo off
REM Compile la classe utilitaire partagee FormSchemaUtil (resolution du schema ODK,
REM utilisee par EditSubmissionServlet et ProjectServlet)
cd /d "%~dp0"
javac -encoding UTF-8 FormSchemaUtil.java
if %ERRORLEVEL% equ 0 (
    echo Compilation reussie.
) else (
    echo Echec de la compilation.
    exit /b 1
)
