"""The router every token-checked endpoint is on, and the one for the live connections.
"""
from fastapi import APIRouter, Depends

from tgflip.account import current_account


api = APIRouter(dependencies=[Depends(current_account)])  # token required on these

# /ws and /logs: the token is their first message (ws_token_ok), not a header
live_router = APIRouter()
