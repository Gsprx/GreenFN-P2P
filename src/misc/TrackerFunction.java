package misc;

public enum TrackerFunction {
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
    };

    public abstract int getEncoded();
}
