"""One-off upload: pushes a local file to the configured R2/S3 bucket (see .env)."""

from __future__ import annotations

import sys
import boto3
from pathlib import Path

import os
sys.path.insert(0, os.path.abspath(os.path.dirname(os.path.dirname(__file__))))

from s3.config import load_settings, r2_configured, r2_endpoint_url

LOCAL_ZIP = Path("/home/nik/Desktop/Flatout2/flatout2_retronexus_rus_v1.zip")
# Object key inside the bucket (logical "folder" prefix optional).
S3_OBJECT_KEY = "Flatout2/flatout2_retronexus_rus_v1.zip"


def _log(msg: str) -> None:
    print(msg, flush=True)


def abort_paused_uploads(settings) -> None:
    """Finds and aborts all incomplete multipart uploads in the bucket."""
    _log(f"Connecting to S3/R2 to find paused uploads in bucket {settings.r2_bucket}...")
    client = boto3.client(
        "s3",
        endpoint_url=r2_endpoint_url(settings),
        aws_access_key_id=settings.r2_access_key,
        aws_secret_access_key=settings.r2_secret_key,
        region_name="auto"
    )
    
    response = client.list_multipart_uploads(Bucket=settings.r2_bucket)
    uploads = response.get("Uploads", [])
    
    if not uploads:
        _log("No ongoing multipart uploads found.")
        return

    _log(f"Found {len(uploads)} ongoing multipart upload(s). Aborting...")
    for upload in uploads:
        key = upload["Key"]
        upload_id = upload["UploadId"]
        _log(f" -> Aborting UploadId: {upload_id} for Key: {key}")
        client.abort_multipart_upload(
            Bucket=settings.r2_bucket,
            Key=key,
            UploadId=upload_id
        )
    _log("Successfully aborted all paused multipart uploads.")


def main() -> None:
    settings = load_settings()
    if not r2_configured(settings):
        sys.stderr.write(
            "R2/S3 is not configured. Set R2_ACCESS_KEY, R2_SECRET_KEY, R2_BUCKET, "
            "and R2_ACCOUNT_ID or S3_API_ENDPOINT in the repo .env.\n"
        )
        raise SystemExit(1)

    if "--abort" in sys.argv:
        abort_paused_uploads(settings)
        return

    if not LOCAL_ZIP.is_file():
        sys.stderr.write(f"File not found: {LOCAL_ZIP}\n")
        raise SystemExit(1)

    size_mb = LOCAL_ZIP.stat().st_size / (1024 * 1024)
    _log(f"Uploading {LOCAL_ZIP} ({size_mb:.1f} MiB) to key {S3_OBJECT_KEY!r} …")

    client = boto3.client(
        "s3",
        endpoint_url=r2_endpoint_url(settings),
        aws_access_key_id=settings.r2_access_key,
        aws_secret_access_key=settings.r2_secret_key,
        region_name="auto"
    )
    
    # Use upload_file with boto3 for large files (it handles multipart automatically)
    client.upload_file(str(LOCAL_ZIP), settings.r2_bucket, S3_OBJECT_KEY)
    
    _log(f"Done: s3://{settings.r2_bucket}/{S3_OBJECT_KEY}")


if __name__ == "__main__":
    main()
