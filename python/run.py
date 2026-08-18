#!/usr/bin/env python
import subprocess
import os
import sys

def main():
    # Resolve project root (one level up from this script)
    project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
    os.chdir(project_root)
    # Install Python dependencies (idempotent)
    try:
        subprocess.run([sys.executable, '-m', 'pip', 'install', '-r', 'python/requirements.txt'], check=True)
    except subprocess.CalledProcessError as e:
        print('Dependency installation failed:', e, file=sys.stderr)
        sys.exit(1)
    # Launch the Python IM server
    try:
        subprocess.Popen([sys.executable, 'python/server.py'])
    except Exception as e:
        print('Failed to start server:', e, file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()
