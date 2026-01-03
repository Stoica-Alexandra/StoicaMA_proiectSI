"""
Uvicorn configuration file to avoid reloading on venv changes
"""
import os

# Base configuration
HOST = "127.0.0.1"
PORT = 8000
RELOAD = True
RELOAD_DIRS = ["src"]  # Only watch the src directory
LOG_LEVEL = "info"

# Environment setup
env_file = os.path.join(os.path.dirname(__file__), ".env")
if os.path.exists(env_file):
    from dotenv import load_dotenv
    load_dotenv(env_file)
