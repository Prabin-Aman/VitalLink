"""Print a dev bearer token for local testing (refused outside ENV=local/test).

    .venv/Scripts/python -m app.devtoken alex@example.com --first Alex --last Carter
    curl -H "Authorization: Bearer <token>" http://localhost:8000/v1/me

The same email always maps to the same account (the subject is derived from the email).
"""

import argparse
import uuid

from app.core.security import mint_dev_token
from app.core.settings import get_settings


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("email")
    parser.add_argument("--first")
    parser.add_argument("--last")
    parser.add_argument("--unconfirmed", action="store_true", help="email not confirmed yet")
    parser.add_argument("--hours", type=int, default=12)
    args = parser.parse_args()
    subject = uuid.uuid5(uuid.NAMESPACE_URL, f"vitallink-dev:{args.email.lower()}")
    print(
        mint_dev_token(
            get_settings(),
            subject=subject,
            email=args.email,
            email_verified=not args.unconfirmed,
            first_name=args.first,
            last_name=args.last,
            ttl=args.hours * 3600,
        )
    )


if __name__ == "__main__":
    main()
