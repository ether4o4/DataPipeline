from .generic import GenericJsonParser


class DiscordParser(GenericJsonParser):
    platform = "discord"
    marker_names = ("messages", "messages.json", "channels", "guilds")
