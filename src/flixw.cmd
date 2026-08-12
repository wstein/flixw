@echo off
rem flixw cmd.exe trampoline -- GENERATED; DO NOT EDIT.  `flixw install` writes it,
rem `flixw doctor --fix` restores it, and `flixw validate` compares it byte for
rem byte.  To change it, edit the CMD text block in flixw.java; src/flixw.cmd in
rem that repository is only the checked-in copy, and tests/lint.sh fails if the two
rem disagree.  Finds an initial java, prefers the compiled stage 0 in the user
rem cache, else launches the source.
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
set "SRC=%ROOT%.flixw\flixw.java"

rem The cache is resolved first: a JDK flixw installed earlier lives in it, and is
rem the last thing worth trying when nothing else answers.
if defined FLIX_CACHE_HOME ( set "CACHE=%FLIX_CACHE_HOME%" ) else (
  set "CACHE=%LOCALAPPDATA%\flixw" )

rem CHOSEN marks an explicitly named JDK: those are obeyed as given, failing
rem included, rather than replaced by one the caller did not ask for.
set "CHOSEN=1"
if defined FLIX_JAVA_HOME ( set "JAVA0=%FLIX_JAVA_HOME%\bin\java.exe" ) else (
if defined JAVA_HOME ( set "JAVA0=%JAVA_HOME%\bin\java.exe" ) else (
set "CHOSEN="
for %%I in (java.exe) do set "JAVA0=%%~$PATH:I" ) )
rem Its path is read from a file rather than guessed: vendors nest differently.
rem It names something this script will execute, so it may only name something
rem inside the directory flixw unpacks into.
rem The marker is cache-controlled text naming something this script will execute,
rem so it is never echoed, called, or otherwise handed back to the parser: cmd
rem metacharacters in it would run before anything could validate the path. The
rem containment test uses delayed expansion alone -- strip the expected prefix,
rem then require the original to be exactly prefix plus remainder, which is a
rem starts-with test that never re-parses the value.
rem The JDK stage 0 resolved for this project last time -- the one that satisfies
rem its java pin. Starting on it avoids the relaunch stage 0 would otherwise need.
rem Machine-specific and git-ignored; writable only by someone who could edit this
rem file anyway, so it adds no trust boundary.
set "NOTED="
if not defined CHOSEN if exist "%ROOT%.flixw\local\java" (
  for /f "usebackq delims=" %%J in ("%ROOT%.flixw\local\java") do (
    if not defined NOTED set "NOTED=%%J" ) )
rem Shape first, and by substring arithmetic rather than by echoing the value:
rem stage 0 writes a normalized path ending in bin\java.exe, so anything else is
rem not a note this wrapper left.
if defined NOTED (
  set "TAIL=!NOTED:bin\java.exe=!"
  if "!TAIL!"=="!NOTED!" set "NOTED="
)
if defined NOTED if not "!NOTED!"=="!TAIL!bin\java.exe" set "NOTED="
if defined NOTED if not "!NOTED!"=="!NOTED:..=!" set "NOTED="
if defined NOTED if not exist "!NOTED!" set "NOTED="
if defined NOTED set "JAVA0=!NOTED!"

set "MINE="
if exist "%CACHE%\jdks\default" (
  for /f "usebackq delims=" %%J in ("%CACHE%\jdks\default") do (
    if not defined MINE set "MINE=%%J" ) )
if defined MINE (
  set "TAIL=!MINE:%CACHE%\jdks\=!"
  if not "!MINE!"=="%CACHE%\jdks\!TAIL!" set "MINE="
)
rem A starts-with test does not say "inside": %CACHE%\jdks\..\..\evil.exe passes one.
rem Any .. at all is refused rather than resolved, since resolving it here would mean
rem handing cache-controlled text back to the parser.
if defined MINE if not "!MINE!"=="!MINE:..=!" set "MINE="
if defined MINE if not exist "!MINE!" set "MINE="
if not defined JAVA0 if defined MINE set "JAVA0=!MINE!"
if not defined JAVA0 (
  echo FLIXW003: no java executable found. Flix needs Java 21+. 1>&2
  echo           Install a JDK -- Eclipse Temurin is the usual choice: 1>&2
  echo             winget install EclipseAdoptium.Temurin.21.JDK 1>&2
  echo             https://adoptium.net/temurin/releases/?version=21 1>&2
  echo           Then set JAVA_HOME, or put its bin directory on PATH. 1>&2
  echo           flixw cannot fetch this first one: it is a Java program 1>&2
  echo           itself, and there is no Java here to run it. Once any Java 16 1>&2
  echo           or newer is reachable, flixw.cmd wrapper --install-jdk fetches 1>&2
  echo           a verified Temurin 21 into the flixw cache. 1>&2
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
rem A version manager's java.exe is a shim with no JDK layout around it, so there is
rem no release file and the version stays unknown. Below 15 that java cannot compile
rem stage 0 either, so the user would see a javac error rather than FLIXW003 or the
rem JDK flixw installed for this case. Ask the JVM once, and only when there is a
rem recorded JDK to switch to, so ordinary runs pay nothing.
if not defined CHOSEN if not defined JFEATURE if defined MINE (
  for /f "tokens=3" %%v in ('cmd /c ""%JAVA0%" -version" 2^>^&1') do (
    if not defined JFEATURE (
      for /f "tokens=1 delims=.-_" %%w in ("%%~v") do set "JFEATURE=%%~w" ) ) )
rem A java below the floor is worse than none: it cannot load the compiled class
rem and, far enough below, cannot compile stage 0 either. Prefer a recorded JDK --
rem but never over an explicitly named one, which must fail loudly instead.
if not defined CHOSEN if defined JFEATURE if !JFEATURE! LSS 21 if defined MINE (
  set "JAVA0=!MINE!"
  set "JFEATURE="
  for %%H in ("!MINE!") do set "JHOME=%%~dpH"
  if exist "!JHOME!..\release" (
    for /f "tokens=2 delims==" %%v in ('findstr /b /c:"JAVA_VERSION=" "!JHOME!..\release" 2^>nul') do (
      for /f "tokens=1 delims=.-" %%w in ("%%~v") do set "JFEATURE=%%~w" ) ) )
set "SLOWPATH=1"
if defined JFEATURE if !JFEATURE! GEQ 21 set "SLOWPATH="

set "H="
for /f "skip=1 delims=" %%L in ('certutil -hashfile "%SRC%" SHA256 2^>nul') do (
  if not defined H set "H=%%L" )
if defined H set "H=!H: =!"
rem Everything that needed delayed expansion is now in ordinary variables, so it
rem is switched off before the launch. With it on, `%*` is rescanned for !...!
rem *after* substitution, and an argument containing an exclamation mark loses
rem part of itself before java is even started: `flixw run "a!b"` arrives as `ab`.
rem The two commands are also kept out of parentheses, because a `)` inside a
rem quoted argument can close a block that a `%*` sits in.
set "CP=!CACHE!\stage0\!H!"
set "FAST="
if not defined SLOWPATH if defined H if exist "!CP!\flixw.class" set "FAST=1"
if defined FAST set "FLIXW_SOURCE=%SRC%"
setlocal disabledelayedexpansion
if defined FAST goto :flixw_fast
"%JAVA0%" "%SRC%" %*
exit /b %ERRORLEVEL%
:flixw_fast
"%JAVA0%" -cp "%CP%" flixw %*
exit /b %ERRORLEVEL%
