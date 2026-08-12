@echo off
REM Compile le filtre FormList (supprime :8080 des URLs dans /formList)
REM Adapter TOMCAT_HOME si Tomcat est ailleurs
set TOMCAT_HOME=c:\xampp\tomcat
set SERVLET_JAR=%TOMCAT_HOME%\lib\servlet-api.jar

if not exist "%SERVLET_JAR%" (
    echo Erreur: servlet-api.jar introuvable. Ajustez TOMCAT_HOME dans ce script.
    exit /b 1
)

cd /d "%~dp0"
javac -encoding UTF-8 -cp "%SERVLET_JAR%" CharResponseWrapper.java FormListUrlRewriteFilter.java
if %ERRORLEVEL% equ 0 (
    echo Compilation reussie. Redemarrez Tomcat puis testez https://hdstiipaalga.ngrok.app/ODKTiipaalga2/formList
) else (
    echo Echec de la compilation.
    exit /b 1
)
