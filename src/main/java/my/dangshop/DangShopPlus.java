package my.dangshop;

public class DangShopPlus extends DangShop {

    @Override
    public void onEnable() {
        super.onEnable();
        new SellMenu(this).register();
    }
}
