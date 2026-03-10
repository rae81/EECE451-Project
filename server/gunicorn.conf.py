import os


bind = f"0.0.0.0:{os.getenv('PORT', '5000')}"
workers = int(os.getenv("WEB_CONCURRENCY", "1"))
threads = int(os.getenv("GUNICORN_THREADS", "100"))
timeout = int(os.getenv("GUNICORN_TIMEOUT", "60"))
accesslog = "-"
errorlog = "-"
capture_output = True
