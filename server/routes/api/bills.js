const router = require('express').Router();
const { Bills } = require('../../models');

router.get('/', (req, res) => {
    Bills.findAll({

    })
        .then(dbBillsData => {
            if (!dbBillsData) {
                res.status(404).json({ message: 'There is no Bills data' })
            }

            res.json(dbBillsData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err)
        })
});

router.post('/', (req, res) => {
    Bills.create({
        name: req.body.name,
        amount: req.body.amount,
        month: req.body.month,
        year: req.body.year,
        user_id: req.session.user_id
    })
        .then(dbBillsData => res.json(dbBillsData))
        .catch(err => {
            console.log(err.message);
            res.status(500).json(err)
        })
});

router.put('/:id', (req, res) => {

    console.log(req.body.isPayed)

    Bills.update(
        { is_payed: req.body.isPayed },
        {
            where: {
                id: req.params.id
            }
        }

    )
        .then(dbBillsData => {
            if (!dbBillsData) {
                res.status(404).json({ message: 'No bill found at this id!' });
                return;
            };

            res.json(dbBillsData)
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
})

router.delete('/:id', (req, res) => {
    Bills.destroy({
        where: {
            id: req.params.id
        }
    })
        .then(data => {
            if (!data) {
                res.status(404).json({ message: 'no bill found!' })
                return;
            }

            res.json(data);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
});

module.exports = router;