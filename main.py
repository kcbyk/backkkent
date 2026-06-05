import logging
import yt_dlp
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# Initialize app
app = FastAPI(
    title="Solenz Media Extractor API",
    description="High-performance, no-storage backend for extracting direct media streams via yt-dlp.",
    version="1.0.0"
)

# Enable CORS for cross-platform clients (Android, Web, etc.)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Logging configuration
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("SolenzBackend")

class ExtractRequest(BaseModel):
    url: str

class ExtractionResponse(BaseModel):
    success: bool
    title: str
    thumbnail: str | None = None
    duration: int | None = 0
    media: dict

@app.get("/")
def read_root():
    return {
        "status": "online",
        "name": "Solenz Media Extractor API",
        "engine": "yt-dlp",
        "message": "Send a POST request to /api/extract with a JSON body: {'url': 'your_link_here'}"
    }

@app.post("/api/extract", response_model=ExtractionResponse)
def extract_media(payload: ExtractRequest):
    url = payload.url.strip()
    if not url:
        raise HTTPException(status_code=400, detail="URL field is required.")
        
    logger.info(f"Extracting metadata for: {url}")
    
    # Configure yt-dlp options (Strictly simulate, do not write files to disk!)
    ydl_opts = {
        'format': 'best',
        'skip_download': True,  # DO NOT DOWNLOAD TO DISK
        'quiet': True,
        'no_warnings': True,
        'user_agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    }
    
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            # Extract info with download=False
            info = ydl.extract_info(url, download=False)
            
            # Extract basic metadata
            title = info.get("title", f"Media_{info.get('id', 'Unknown')}")
            thumbnail = info.get("thumbnail") or info.get("thumbnails", [{}])[0].get("url")
            duration = info.get("duration")
            
            # Find the best format URLs
            formats = info.get("formats", [])
            
            video_url = None
            audio_url = None
            
            # Try to query direct audio-only and video+audio paths
            audio_formats = [f for f in formats if f.get("vcodec") == "none" and f.get("acodec") != "none" and f.get("url")]
            video_formats = [f for f in formats if f.get("vcodec") != "none" and f.get("url")]
            
            # Fallback checks / Quality Sorts
            if audio_formats:
                # Get highest quality audio link
                audio_formats.sort(key=lambda x: x.get("abr", 0) or x.get("tbr", 0) or 0, reverse=True)
                audio_url = audio_formats[0]["url"]
            
            if video_formats:
                # Get highest quality combined video link or standalone video stream
                video_formats.sort(key=lambda x: (x.get("height", 0) or 0) + (x.get("tbr", 0) or 0), reverse=True)
                video_url = video_formats[0]["url"]
                
            # If no separated files found, fallback to generic result url
            if not video_url:
                video_url = info.get("url")
            if not audio_url:
                audio_url = info.get("url")
                
            # Construct output matching Şenol's exact specifications
            return ExtractionResponse(
                success=True,
                title=title,
                thumbnail=thumbnail or "",
                duration=duration or 0,
                media={
                    "video_url": video_url or "",
                    "audio_url": audio_url or ""
                }
            )
            
    except Exception as e:
        logger.error(f"Failed extraction: {str(e)}")
        raise HTTPException(
            status_code=400,
            detail=f"Medya çözümlenirken hata oluştu: {str(e)}"
        )
