from pathlib import Path
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    DATABASE_URL: str = "sqlite:///./nlp_ocr.db"

    UPLOAD_DIR: str = "./uploads"
    PDF_DIR: str = "./pdfs"

    ARUCO_DICT: str = "DICT_4X4_50"
    MARKER_SIZE_PX: int = 60
    MARKER_MARGIN_PX: int = 40

    DEFAULT_DPI: int = 150
    DEFAULT_PHYSICAL_PAGE: str = "A4"

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


settings = Settings()

Path(settings.UPLOAD_DIR).mkdir(parents=True, exist_ok=True)
Path(settings.PDF_DIR).mkdir(parents=True, exist_ok=True)
