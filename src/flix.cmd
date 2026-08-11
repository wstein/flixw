@echo off
rem flixw cmd.exe trampoline -- invariant file.  Finds an initial java, prefers the
rem compiled stage 0 in the user cache, else launches the source.
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
set "SRC=%ROOT%.flix-wrapper\flix.java"

if defined FLIX_JAVA_HOME ( set "JAVA0=%FLIX_JAVA_HOME%\bin\java.exe" ) else (
if defined JAVA_HOME ( set "JAVA0=%JAVA_HOME%\bin\java.exe" ) else (
for %%I in (java.exe) do set "JAVA0=%%~$PATH:I" ) )
if not defined JAVA0 (
  echo FLIXW003: no java executable found. Flix needs Java 21+. 1>&2
  exit /b 127 )
if not exist "%JAVA0%" (
  echo FLIXW003: %JAVA0% not found. 1>&2
  exit /b 127 )
if not exist "%SRC%" (
  echo FLIXW009: missing %SRC% 1>&2
  exit /b 88 )

rem Feature version of the selected java, from the release file of its own JDK.
rem Only used to decide whether the compiled class is loadable: a JVM below the
rem floor cannot load it and exec leaves no way back.  Unknown changes nothing.
set "JHOME=%JAVA0:\bin\java.exe=%"
set "JFEATURE="
if exist "%JHOME%\release" (
  for /f "tokens=2 delims==" %%v in ('findstr /b /c:"JAVA_VERSION=" "%JHOME%\release" 2^>nul') do (
    for /f "tokens=1 delims=.-" %%w in ("%%~v") do set "JFEATURE=%%~w" ) )
rem Unknown is not good enough: a java that is a shim script rather than a JDK
rem layout has no release file, and running the class blind fails on class file
rem version with no way back.  Default to the source path; earn the fast one.
set "SLOWPATH=1"
if defined JFEATURE if !JFEATURE! GEQ 21 set "SLOWPATH="

if defined FLIX_CACHE_HOME ( set "CACHE=%FLIX_CACHE_HOME%" ) else (
  set "CACHE=%LOCALAPPDATA%\flixw" )
set "H="
for /f "skip=1 delims=" %%L in ('certutil -hashfile "%SRC%" SHA256 2^>nul') do (
  if not defined H set "H=%%L" )
if defined H set "H=!H: =!"
if not defined SLOWPATH if defined H if exist "!CACHE!\stage0\!H!\flix.class" (
  set "FLIXW_SOURCE=%SRC%"
  "%JAVA0%" -cp "!CACHE!\stage0\!H!" flix %*
  exit /b !ERRORLEVEL! )
"%JAVA0%" "%SRC%" %*
exit /b !ERRORLEVEL!
