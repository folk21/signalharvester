#!/usr/bin/env bash 

zip -r ../signalharvester_FULL.zip . -x ".idea/*" ".venv/*" ".git/*" ".kotlin/*" ".gradle/*" "build/*" "*/.structurizr/*" "*/build/*" "*/target/*" "*/__pycache__/*" "*/run21ai_system_tests.egg-info/*" "*/.pytest_cache/*" "*/node_modules/*" "*.class" "*.zip"
