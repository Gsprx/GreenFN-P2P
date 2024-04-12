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

    CHECK_ACTIVE {
        @Override
        public int getEncoded() {
            return 7;
        }
    };

    public abstract int getEncoded();
}
