@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 物联卡流量服务
node server.js
pause
