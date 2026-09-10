from pathlib import Path

from dotenv import load_dotenv
from openai import OpenAI


BACKEND_DIR = Path(__file__).resolve().parent

load_dotenv(
    BACKEND_DIR / ".env",
    override=True
)

client = OpenAI()

response = client.responses.create(
    model="gpt-5.6-luna",
    input="Reply with exactly: OpenAI API works"
)

print(response.output_text)