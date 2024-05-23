package misc;

public enum Function {
    REGISTER {
        @Override
        public int getEncoded() {
            return 1;
        }
    },

    LOGIN {
        @Override
        public int getEncoded() {
            return 2;
        }
    },

    LOGOUT {
        @Override
        public int getEncoded() {
            return 3;
        }
    },

    PEER_INFORM {
        @Override
        public int getEncoded() {
            return 4;
        }
    },

    PEER_NOTIFY {
        @Override
        public int getEncoded() {
            return 5;
        }
    },

    REPLY_LIST {
        @Override
        public int getEncoded() {
            return 6;
        }
    },

    REPLY_DETAILS {
        @Override
        public int getEncoded() {
            return 7;
        }
    },
    SIMPLE_DOWNLOAD {
        @Override
        public int getEncoded() {
            return 8;
        }
    },

    CHECK_ACTIVE {
        @Override
        public int getEncoded() {
            return 10;
        }
    },

    SEEDER_INFORM {
        @Override
        public int getEncoded() {
            return 11;
        }
    },

    COLLABORATIVE_DOWNLOAD_HANDLER {
        @Override
        public int getEncoded() { return 12; }
    };

    public abstract int getEncoded();
}
//green fn
