from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    _project_root: Path = Path(__file__).resolve().parents[1]
    model_config = SettingsConfigDict(
        # Use absolute path so deployed runs don't depend on cwd
        env_file=str(_project_root / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    

    # Cloudflare R2 / S3-compatible storage (for YouTube pipeline + large outputs)
    r2_account_id: str | None = Field(default=None, alias="R2_ACCOUNT_ID")
    r2_access_key: str | None = Field(default=None, alias="R2_ACCESS_KEY")
    r2_secret_key: str | None = Field(default=None, alias="R2_SECRET_KEY")
    r2_bucket: str | None = Field(default=None, alias="R2_BUCKET")
    s3_api_endpoint: str | None = Field(default=None, alias="S3_API_ENDPOINT")
    s3_presign_expires_seconds: int = Field(default=3600, alias="S3_PRESIGN_EXPIRES_SECONDS")
    s3_temp_object_ttl_seconds: int = Field(default=3600, alias="S3_TEMP_OBJECT_TTL_SECONDS")
    s3_cleanup_interval_seconds: int = Field(default=120, alias="S3_CLEANUP_INTERVAL_SECONDS")

    # Debug / ops
    log_incoming_file_id: bool = Field(default=True, alias="LOG_INCOMING_FILE_ID")
    echo_incoming_file_id_to_user: bool = Field(default=False, alias="ECHO_INCOMING_FILE_ID_TO_USER")
    log_dubbed_result_file_id: bool = Field(default=True, alias="LOG_DUBBED_RESULT_FILE_ID")

  

def load_settings() -> Settings:
    return Settings()


def r2_endpoint_url(settings: Settings) -> str | None:
    """S3 API endpoint: explicit S3_API_ENDPOINT or default R2 URL from R2_ACCOUNT_ID."""
    ep = (settings.s3_api_endpoint or "").strip()
    if ep:
        return ep
    aid = (settings.r2_account_id or "").strip()
    if aid:
        return f"https://{aid}.r2.cloudflarestorage.com"
    return None


def r2_configured(settings: Settings) -> bool:
    return bool(
        settings.r2_access_key
        and settings.r2_secret_key
        and settings.r2_bucket
        and r2_endpoint_url(settings)
    )

